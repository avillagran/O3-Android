package dev.avillagran.ot3android.core.bridge

import dev.avillagran.ot3android.core.transport.DomainCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Supplied by an authenticated bridge session, never by a label, URL hint or desktop token. */
class BridgeCatalogIdentity(val bridgeId: String, val clientId: String)

/** Transport must verify the paired publisher and recipient before returning a snapshot. */
fun interface AuthenticatedBridgeCatalogSource {
    suspend fun snapshot(identity: BridgeCatalogIdentity): JSONObject
}

/** A private cache slice per publisher + client. Implementations replace it atomically. */
interface BridgeCatalogCache {
    suspend fun read(identity: BridgeCatalogIdentity): JSONObject?
    suspend fun replace(identity: BridgeCatalogIdentity, snapshot: JSONObject)
}

/** Discovery is distinct from authorization. This cache NEVER contains T3 bearer credentials. */
sealed interface BridgeCatalogSyncState {
    data object NotLoaded : BridgeCatalogSyncState
    class Available(val catalog: JSONObject) : BridgeCatalogSyncState
    /** Keep the last known identities on transport failure; never interpret failure as deletion. */
    class Unavailable(val cached: JSONObject?) : BridgeCatalogSyncState
}

/**
 * Host lifecycle/cache adapter. Rust validates identities, routes, freshness, revisions and
 * withdrawals. This adapter deliberately cannot write/delete local EnvironmentRepository
 * entries, credentials, drafts or cursors. A route is not an authorization to read a host.
 *
 * Integration requires the authenticated remote catalog/enrollment contract; it is not wired
 * to pairing in builds whose bridge does not implement that contract.
 */
class BridgeCatalogSynchronizer(
    private val identity: BridgeCatalogIdentity,
    private val source: AuthenticatedBridgeCatalogSource,
    private val cache: BridgeCatalogCache,
    private val domain: DomainCalls,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<BridgeCatalogSyncState>(BridgeCatalogSyncState.NotLoaded)
    val state: StateFlow<BridgeCatalogSyncState> = mutableState.asStateFlow()

    suspend fun refresh(): BridgeCatalogSyncState = mutex.withLock {
        var previous: JSONObject? = null
        try {
            previous = cache.read(identity)
            val incoming = source.snapshot(identity)
            val reconciled = domain.domain(
                "connectionCatalog.reconcileBridge",
                JSONObject().put("snapshot", incoming).put("cache", previous ?: JSONObject())
                    .put("context", JSONObject().put("bridgeId", identity.bridgeId)
                        .put("clientId", identity.clientId).put("now", clock())),
            ) as? JSONObject ?: error("invalid_bridge_catalog_result")
            cache.replace(identity, reconciled)
            BridgeCatalogSyncState.Available(reconciled).also { mutableState.value = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No exception text/JSON is exposed: an upstream error may contain secrets.
            BridgeCatalogSyncState.Unavailable(previous).also { mutableState.value = it }
        }
    }
}
