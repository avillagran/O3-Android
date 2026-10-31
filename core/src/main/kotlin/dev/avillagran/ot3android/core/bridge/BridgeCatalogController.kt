package dev.avillagran.ot3android.core.bridge

import androidx.room.withTransaction
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.data.local.BridgeManagedEntity
import dev.avillagran.ot3android.core.data.local.EnvironmentEntity
import dev.avillagran.ot3android.core.data.local.NATIVE_AUTHORIZED
import dev.avillagran.ot3android.core.data.local.NATIVE_EXPIRED
import dev.avillagran.ot3android.core.data.local.Ot3Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class BridgeDeviceIdentity(val clientId: String, val bridgeId: String?)

/** Host lifecycle and Room adapter for the native manager; never mints credentials. */
class BridgeCatalogController(
    private val database: Ot3Database,
    private val connections: ConnectionManager,
    private val scope: CoroutineScope,
    private val tokens: suspend () -> dev.avillagran.ot3android.core.security.TokenStore,
    private val native: suspend (JSONObject) -> JSONObject = NativeBridge::request,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mutations: Mutex,
    private val stopConnection: suspend (String) -> Unit = connections::forget,
) {
    private val mutex = Mutex()
    @Volatile private var handle: Long? = null
    @Volatile private var selected = false
    @Volatile private var foreground = false
    private var clientId: String? = null
    private var stateDirectory: String? = null
    private var polling: Job? = null
    private val mutableIdentity = MutableStateFlow<BridgeDeviceIdentity?>(null)
    val identity = mutableIdentity.asStateFlow()
    private val mutableStatus = MutableStateFlow("not_loaded")
    val status = mutableStatus.asStateFlow()

    suspend fun open(privateStateDirectory: String, directOnly: Boolean = false): String = mutex.withLock {
        if (handle != null) {
            check(stateDirectory == privateStateDirectory)
            return@withLock checkNotNull(clientId)
        }
        val result = native(JSONObject().put("op", "open").put("stateDir", privateStateDirectory).put("directOnly", directOnly))
        handle = result.getLong("handle")
        clientId = result.getString("clientId")
        mutableIdentity.value = BridgeDeviceIdentity(checkNotNull(clientId), result.optString("bridgeId").takeIf { !result.isNull("bridgeId") })
        stateDirectory = privateStateDirectory
        selected = !result.isNull("bridgeId")
        if (selected) mutableStatus.value = invoke("cached").getString("status")
        if (foreground && selected) setForeground(true)
        checkNotNull(clientId)
    }

    suspend fun pair(invitation: JSONObject): Boolean = mutex.withLock {
        invoke("pair", JSONObject().put("invitation", invitation)).getBoolean("approved")
    }

    suspend fun selectBridge(bridgeId: String) = mutex.withLock {
        require(bridgeId.length == 64 && bridgeId.all { it in '0'..'9' || it in 'a'..'f' }) { "invalid_peer_id" }
        invoke("selectBridge", JSONObject().put("bridgeId", bridgeId))
        mutableIdentity.value = BridgeDeviceIdentity(checkNotNull(clientId), bridgeId)
        mutableStatus.value = invoke("cached").getString("status")
        selected = true
        if (foreground) setForeground(true)
    }

    suspend fun refresh() = mutex.withLock {
        mutableStatus.value = invoke("refresh").getString("status")
        val dao = database.environmentDao()
        val managed = database.bridgeManagedDao()
        val (origins, previous) = mutations.withLock {
            managed.all().associateBy { it.id } to dao.observeAll().first()
        }
        val local = JSONArray()
        previous.forEach { row ->
            val origin = origins[row.id]
            local.put(JSONObject().put("id", row.id).put("environmentId", row.environmentId)
                .put("baseUrl", row.baseUrl).put("provenance", origin?.provenance).put("status", origin?.status)
                .put("authorizationExpiresAt", origin?.takeIf { it.status in SESSION_STATUSES }?.authorizationExpiresAt))
        }
        val plan = invoke("projection", JSONObject().put("local", local))
        val stops = plan.getJSONArray("stop")
        // Await stream termination outside Room's transaction: shutdown may write cursors.
        val stopped = (0 until stops.length()).map { stops.getString(it) }
        stopped.forEach { stopConnection(it) }
        val withdrawn = mutableListOf<String>()
        val rerouted = mutableListOf<String>()
        mutations.withLock {
            database.withTransaction {
                val rows = plan.getJSONArray("upserts")
                var nextOrder = (previous.maxOfOrNull { it.sortOrder } ?: -1) + 1
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val id = row.getString("id")
                    val old = previous.firstOrNull { it.id == id }
                    val latest = dao.findById(id)
                    // Explicit ownership changes and removals win. Connection timestamps
                    // may change while streams stop; they must not suppress a withdrawal.
                    if (managed.find(id) != origins[id] ||
                        old?.copy(lastConnectedAt = latest?.lastConnectedAt) != latest) continue
                    // The native plan chooses ownership and route. This adapter preserves
                    // all host-owned state, including local IDs, order, cursors and drafts.
                    dao.upsert(EnvironmentEntity(id, row.getString("label"),
                        if (row.isNull("baseUrl")) "" else row.getString("baseUrl"),
                        row.getString("environmentId"), latest?.createdAt ?: clock(),
                        latest?.lastConnectedAt, latest?.sortOrder ?: nextOrder++))
                    managed.upsert(BridgeManagedEntity(id, row.getString("provenance"), row.getString("status"),
                        if (row.isNull("authorizationExpiresAt")) null else row.getLong("authorizationExpiresAt")))
                    if (row.getString("status") == "revoked") withdrawn += id
                    // A native row outlives a route change and no enrollment will restart it.
                    else if (row.getString("status") == NATIVE_AUTHORIZED && id in stopped && !row.isNull("baseUrl")) rerouted += id
                }
            }
        }
        withdrawn.forEach { withdraw(it) }
        rerouted.forEach { connections.startIfCurrent(connections.supervisor(it)) }
        val needsAuthority = { mutableStatus.value = "available_requires_authority" }
        attempt(plan.getJSONArray("enroll"), needsAuthority, ::enrollUnlocked)
        // SDKs older than the all-hosts path answer without these two lists.
        attempt(plan.optJSONArray("hostEnroll"), needsAuthority, ::hostPairUnlocked)
        // A renewal can wait for the next refresh: the row keeps what it has.
        attempt(plan.optJSONArray("renew"), {}, ::hostPairUnlocked)
    }

    private suspend fun attempt(ids: JSONArray?, onFailure: () -> Unit, enrollment: suspend (String) -> Unit) {
        if (ids == null) return
        for (i in 0 until ids.length()) {
            try {
                enrollment(ids.getString(i))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }

    /**
     * Runs after "revoked" commits, when the gate already refuses a new connection: one that
     * started during the plan's shutdown stops here. A failed delete stays denied, and every
     * refresh retries it for as long as the row is withdrawn.
     */
    private suspend fun withdraw(id: String) {
        try {
            stopConnection(id)
            mutations.withLock {
                // An explicit local credential saved meanwhile is not the bridge's to remove.
                if (database.bridgeManagedDao().find(id)?.status == "revoked") tokens().delete(id)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { }
    }

    /** One bridge-approved enrollment per environment; no manual pairing code per host. */
    suspend fun enroll(environmentId: String) = mutex.withLock { enrollUnlocked(environmentId) }

    private suspend fun enrollUnlocked(environmentId: String) =
        install(invoke("enroll", JSONObject().put("environmentId", environmentId)), "authorized")

    /** Enrolls an all-hosts environment or renews its session: both are a new write session. */
    private suspend fun hostPairUnlocked(environmentId: String) =
        install(invoke("hostPair", JSONObject().put("environmentId", environmentId)), NATIVE_AUTHORIZED)

    private suspend fun install(receipt: JSONObject, status: String) {
        val managed = database.bridgeManagedDao()
        val (row, origin) = mutations.withLock {
            val origins = managed.all().associateBy { it.id }
            val row = database.environmentDao().observeAll().first().firstOrNull {
                it.environmentId == receipt.getString("environmentId") &&
                    origins[it.id]?.provenance == receipt.getString("provenance")
            } ?: return
            val origin = checkNotNull(origins[row.id])
            // A read-only session must never replace a write session.
            if (status == "authorized" && (origin.status == NATIVE_AUTHORIZED || origin.status == NATIVE_EXPIRED)) return
            row to origin
        }
        stopConnection(row.id)
        try {
            mutations.withLock {
                // Stream shutdown suspends. An explicit local save/removal wins and
                // must never receive the old bridge's credential or ownership.
                if (managed.find(row.id) != origin || database.environmentDao().findById(row.id)
                        ?.copy(lastConnectedAt = row.lastConnectedAt) != row) return@withLock
                managed.upsert(origin.copy(status = "credential_updating"))
                try {
                    tokens().setToken(row.id, receipt.getString("accessToken"))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Whichever session the store kept is a write session for this host.
                    if (origin.status == NATIVE_AUTHORIZED && (origin.authorizationExpiresAt ?: 0L) > clock()) managed.upsert(origin)
                    throw failure
                }
                managed.upsert(origin.copy(status = status, authorizationExpiresAt = receipt.getLong("expiresAt")))
            }
        } finally {
            // Also after a refused install: the stream it stopped may still be entitled.
            connections.startIfCurrent(connections.supervisor(row.id))
        }
    }

    /** Initial load, resume and reconnect all call the same serialized refresh. */
    @Synchronized
    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        polling?.cancel()
        polling = if (foreground && handle != null && selected) scope.launch {
            while (isActive) {
                try { refresh() } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { mutableStatus.value = "offline" }
                delay(30_000)
            }
        } else null
    }

    fun reconnect() { if (polling != null) setForeground(true) }

    suspend fun close() {
        polling?.cancelAndJoin()
        polling = null
        mutex.withLock {
            if (handle != null) invoke("close")
            handle = null
            clientId = null
            mutableIdentity.value = null
            stateDirectory = null
            selected = false
        }
    }

    private suspend fun invoke(op: String, request: JSONObject = JSONObject()): JSONObject =
        native(request.put("op", op).put("handle", checkNotNull(handle)))

    private companion object {
        /** The native projection reads an expiry only from rows holding a session. */
        val SESSION_STATUSES = setOf("authorized", NATIVE_AUTHORIZED, NATIVE_EXPIRED)
    }
}
