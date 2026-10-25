package dev.avillagran.ot3android.core.pairing

import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.transport.PairingClient
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.ot3android.core.transport.T3TransportFactory
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the user can do about a failed pairing, grouped from the lib's error codes. */
enum class PairingFailure {
    /** Not a pairing link or server address (`invalid_endpoint`). */
    InvalidLink,

    /** The link carries no pairing code. Checked here, before anything is sent. */
    NoCode,

    /** The code is malformed (`invalid_pairing_code`). */
    InvalidCode,

    /** The server refused the code: unknown, already used or expired (`pairing_rejected`). */
    Rejected,

    /** Another environment answers at that address now (`t3_environment_mismatch`). */
    WrongServer,

    /** A T3 server that cannot pair this way (`invalid_t3_environment_id`, `unsupported_pairing_token`). */
    Incompatible,

    /** No answer from the address (`unreachable`, `rpc_connection_failed`). */
    Unreachable,

    /** Something answered, but not a T3 server (`not_json`, `malformed`, `http_<n>`). */
    NotT3,

    /** A bug or a local failure (`invalid_pairing_client`, `internal_error`, storage). */
    Internal,

    /** Any other code. */
    Other,
    ;

    companion object {
        fun of(code: String): PairingFailure = when {
            code == "invalid_endpoint" -> InvalidLink
            code == "invalid_pairing_code" -> InvalidCode
            code == "pairing_rejected" -> Rejected
            code == "t3_environment_mismatch" -> WrongServer
            code == "invalid_t3_environment_id" || code == "unsupported_pairing_token" -> Incompatible
            code == "unreachable" || code == "rpc_connection_failed" -> Unreachable
            code == "not_json" || code == "malformed" || code.startsWith("http_") -> NotT3
            code == "invalid_pairing_client" || code == ConnectionSupervisor.INTERNAL_ERROR -> Internal
            else -> Other
        }
    }
}

/**
 * Where a pairing is. No state ever holds the pairing code or the issued token: the code stays
 * inside [PairingService] between [PairingState.Ready] and [PairingService.confirm], and the
 * token goes straight to the credential store.
 */
sealed interface PairingState {
    data object Idle : PairingState

    /** Reading the descriptor at [origin]. */
    data class Inspecting(val origin: String) : PairingState

    /**
     * A T3 server answers at [origin]; waiting for the user to confirm. [existingLabel] and
     * [existingBaseUrl] describe an environment already saved for the same server (by the
     * descriptor's `environmentId`, which anyone can claim). At the same address pairing
     * replaces its token; when it [moves], pairing adds a new environment unless the user
     * explicitly replaces the saved one.
     */
    data class Ready(
        val origin: String,
        val server: ServerDescriptor,
        val existingLabel: String?,
        val existingBaseUrl: String? = null,
    ) : PairingState {
        /** The saved environment for this server is at another address. */
        val moves: Boolean get() = existingBaseUrl != null && existingBaseUrl != origin
    }

    /** Exchanging the code and saving the environment. */
    data class Pairing(val origin: String, val server: ServerDescriptor) : PairingState

    /** Saved as [environmentId] (the local catalog id); [updated] when it replaced a saved token. */
    data class Paired(val environmentId: String, val label: String, val updated: Boolean) : PairingState

    /** [code] is the lib's code (or a local one), safe to show; [origin] when the link was readable. */
    data class Failed(val failure: PairingFailure, val code: String, val origin: String?) : PairingState
}

/**
 * Pairs this device with a T3 server from a pairing link: parse ([PairingLink], offline) →
 * [inspect] reads the descriptor (label, version) → the user confirms → [confirm] exchanges the
 * one-time code with `pairBearer` (no connection), stores the environment (local row keyed by
 * the server's environment id, label from the descriptor) and the issued long-lived token, then
 * calls [handOver] so the connection layer picks it up. An environment already saved for the
 * same server at the same address keeps its row, label and id and gets the new token. One at
 * another address is never moved silently: the descriptor that matched it is unauthenticated,
 * so pairing adds a new environment unless [confirm] is told to replace the saved one.
 *
 * Each step uses a fresh transport, closed afterwards. One step runs at a time; [confirm] is
 * not cancellable once it starts (see there). Nothing here logs; the pairing code and the token never reach [state] or a [toString].
 */
class PairingService(
    private val transportFactory: T3TransportFactory,
    private val repository: EnvironmentRepository,
    private val client: PairingClient,
    private val handOver: suspend (environmentId: String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private val lock = Mutex()

    /** The parsed link while [state] is [PairingState.Ready] or pairing. Holds the code. */
    private var target: PairingTarget? = null

    /** The saved environment for the same server, found by [inspect]. */
    private var existing: Environment? = null

    /** Parses [link] (a code in it, or [code] for a bare address) and reads the server's descriptor. */
    suspend fun inspect(link: String, code: String = ""): PairingState = lock.withLock {
        target = null
        existing = null
        val parsed = try {
            PairingLink.parse(link, code)
        } catch (error: T3Exception) {
            return@withLock fail(error.code, origin = null)
        }
        if (parsed.pairingCode.isEmpty()) return@withLock fail(PairingFailure.NoCode, NO_CODE, parsed.httpBaseUrl)
        val origin = parsed.httpBaseUrl
        _state.value = PairingState.Inspecting(origin)
        step(origin) {
            val server = ServerDescriptor.from(withTransport { it.fetchDescriptor(origin) })
            val saved = server.environmentId?.let { id -> repository.environments.first().firstOrNull { it.environmentId == id } }
            target = parsed
            existing = saved
            PairingState.Ready(origin, server, saved?.label, saved?.baseUrl)
        }
    }

    /**
     * Pairs with the server [inspect] found; ignored unless [state] is [PairingState.Ready].
     * When [PairingState.Ready.moves], a new environment is added unless [replace] is true,
     * the user's explicit choice to move the saved one to the new address.
     */
    suspend fun confirm(replace: Boolean = false): PairingState = lock.withLock {
        val ready = _state.value as? PairingState.Ready ?: return@withLock _state.value
        val parsed = target ?: return@withLock _state.value
        val match = existing?.takeIf { it.baseUrl == parsed.httpBaseUrl || replace }
        target = null
        existing = null
        _state.value = PairingState.Pairing(ready.origin, ready.server)
        // Once confirmed, the pairing completes even if the caller (the screen) goes away: the
        // exchange spends the code, so stopping half-way could only lose the issued session.
        withContext(NonCancellable) {
            step(ready.origin) {
                val paired = withTransport { transport ->
                    transport.pairBearer(
                        baseUrl = parsed.httpBaseUrl,
                        pairingCode = parsed.pairingCode,
                        environmentId = ready.server.environmentId,
                        client = client,
                        connect = false,
                    )
                }
                val server = ServerDescriptor.from(paired.descriptor)
                // Removed since inspect: added again as a new environment.
                val updating = match?.let { repository.find(it.id) }
                val label = updating?.label ?: server.label ?: ready.server.label ?: ready.origin.substringAfter("://")
                val saved = repository.save(
                    id = updating?.id,
                    label = label,
                    baseUrl = parsed.httpBaseUrl,
                    environmentId = paired.environmentId,
                    token = paired.accessToken,
                    matchServer = false,
                )
                handOver(saved.id)
                PairingState.Paired(saved.id, saved.label, updated = updating != null)
            }
        }
    }

    /** Back to [PairingState.Idle], forgetting a parsed link. */
    suspend fun reset() = lock.withLock {
        target = null
        existing = null
        _state.value = PairingState.Idle
    }

    private suspend fun step(origin: String, block: suspend () -> PairingState): PairingState = try {
        block().also { _state.value = it }
    } catch (error: T3Exception) {
        fail(error.code, origin)
    } catch (error: CancellationException) {
        _state.value = PairingState.Idle
        throw error
    } catch (_: Exception) {
        // Not a lib failure (no stable code); its message is not shown or kept.
        fail(PairingFailure.Internal, ConnectionSupervisor.INTERNAL_ERROR, origin)
    }

    private fun fail(code: String, origin: String?) = fail(PairingFailure.of(code), code, origin)

    private fun fail(failure: PairingFailure, code: String, origin: String?): PairingState =
        PairingState.Failed(failure, code, origin).also { _state.value = it }

    /** Creating a native transport allocates a lib handle: never on the caller's (main) thread. */
    private suspend fun <T> withTransport(block: suspend (T3Transport) -> T): T {
        val transport = withContext(io) { transportFactory.create() }
        try {
            return block(transport)
        } finally {
            withContext(NonCancellable + io) { transport.close() }
        }
    }

    companion object {
        /** [PairingState.Failed.code] when the link has no pairing code. */
        const val NO_CODE = "no_pairing_code"
    }
}
