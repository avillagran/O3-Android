package dev.avillagran.ot3android.core.connection

import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.shell.ShellReducer
import dev.avillagran.ot3android.core.shell.ShellState
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.ot3android.core.transport.T3TransportFactory
import dev.avillagran.ot3android.core.transport.shellCursor
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** Connection state of one environment, as the UI shows it. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState

    /** Authenticated; the shell stream is open or being resumed. */
    data class Connected(val descriptor: ServerDescriptor) : ConnectionState

    /** The token was rejected (401 or 403). No automatic retry: the user edits the token. */
    data object Unauthorized : ConnectionState

    /**
     * The last attempt failed with the lib's [error] code. The next attempt runs at
     * [nextRetryAt] (epoch ms of the supervisor's clock), or never when null (the error
     * cannot fix itself: wrong URL, other server, missing token).
     */
    data class Unreachable(val error: String, val nextRetryAt: Long?) : ConnectionState
}

/**
 * Keeps one environment connected and its shell current. Owns one [T3Transport] while
 * running. A run is: `fetchDescriptor` → `connect(Bearer)` (no I/O) → shell GET (the first
 * request, so a bad token surfaces here as `unauthorized`) → shell stream from the shell's
 * sequence. When the stream ends it reconnects after a jittered [Backoff]:
 *
 * - a cancel (`closed` with no error: the session was switched or dropped under it) is not
 *   a failure: it reconnects after [CANCEL_REOPEN_MS], keeping the shell and the cursor and
 *   without counting a backoff step;
 * - transient errors ([TRANSIENT]) keep the held shell and resume after its sequence;
 * - `unauthorized` stops and waits for [retry];
 * - [FATAL] errors stop with no retry time;
 * - anything else (`stream_closed`, `http_<n>`, ...) drops the shell and starts over with a
 *   fresh snapshot. An exception that is not a [T3Exception] (a bug, or a lib failure
 *   without a stable code) counts as [INTERNAL_ERROR]: it backs off and starts over too.
 *
 * Stream updates are applied with [ShellReducer.reduce] and the resume cursor follows
 * `shellCursor`, both through the `ShellUpdates.kt` adapter, so a metadata-only snapshot
 * never replaces the shell or moves the cursor. [shell] is published only on the update's
 * `repaint`, never mid-chunk. The cursor is persisted in [cursorStore] on repaint.
 *
 * Pure Kotlin (no Android types); runs in [scope]. The token is read through [token] at each
 * run and never logged.
 */
class ConnectionSupervisor(
    environment: Environment,
    private val token: suspend () -> String?,
    private val cursorStore: ShellCursorStore,
    private val transportFactory: T3TransportFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val onConnected: suspend (ServerDescriptor) -> Unit = {},
    /** Where the (blocking) transport close runs. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val environmentId: String = environment.id
    private val baseUrl = environment.baseUrl

    /** The server's environment id, checked by `fetchDescriptor` once known. */
    @Volatile private var serverEnvironmentId: String? = environment.environmentId

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _shell = MutableStateFlow<ShellState?>(null)

    /** The reduced shell, null until the first snapshot (or after a reset). */
    val shell: StateFlow<ShellState?> = _shell.asStateFlow()

    /** The shell as applied so far; [shell] catches up with it on repaint. */
    private var held: ShellState? = null

    /** Where the shell stream resumes (`afterSequence`) for [held]; see `shellCursor`. */
    private var cursor: Long? = null
    private val shellLock = Mutex()
    private var persistedCursor: Long? = null

    @Volatile private var transport: T3Transport? = null
    private var job: Job? = null
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    val isRunning: Boolean get() = job?.isActive == true

    /** Starts connecting unless already running. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** Stops and closes the transport. The shell stays visible and resumes on [start]. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _state.value = ConnectionState.Disconnected
    }

    /** Retries at once: skips a pending backoff, or starts again after `unauthorized` or a fatal error. */
    fun retry() {
        if (isRunning) retryNow.trySend(Unit) else start()
    }

    /**
     * Probes the open streams (on resume or a network change; a dead socket then ends with
     * `stream_timeout` within about 3 s) and skips a pending backoff.
     */
    suspend fun wake() {
        runCatching { transport?.wakeSubscriptions() }
        if (_state.value is ConnectionState.Unreachable && isRunning) retryNow.trySend(Unit)
    }

    /**
     * Replaces the shell with a fresh shell GET (pull to refresh). Fails with the lib's
     * `T3Exception` when not connected or the request fails.
     */
    suspend fun refresh() {
        val current = transport
        if (current == null || _state.value !is ConnectionState.Connected) {
            retry()
            throw T3Exception("not_connected")
        }
        val fresh = ShellReducer.parse(current.shell())
        publish(fresh)
    }

    private suspend fun run() {
        val backoff = Backoff(random)
        val client = transportFactory.create()
        transport = client
        try {
            while (true) {
                val error = attempt(client, backoff)
                currentCoroutineContext().ensureActive()
                if (error == CANCELLED) {
                    // Still running, so not our stop(): reopen from the held cursor. The
                    // fixed pause keeps a handle that cancels at once from spinning.
                    delay(CANCEL_REOPEN_MS)
                    continue
                }
                when {
                    error == UNAUTHORIZED -> {
                        _state.value = ConnectionState.Unauthorized
                        return
                    }
                    error in FATAL -> {
                        _state.value = ConnectionState.Unreachable(error, nextRetryAt = null)
                        return
                    }
                    error !in TRANSIENT -> reset()
                }
                val delayMs = backoff.nextDelayMs()
                _state.value = ConnectionState.Unreachable(error, clock() + delayMs)
                while (retryNow.tryReceive().isSuccess) Unit
                withTimeoutOrNull(delayMs) { retryNow.receive() }
            }
        } catch (error: Exception) {
            // Never end silently: the state says so, retry() starts over, and the scope's
            // exception handler logs the class.
            if (error !is CancellationException) _state.value = ConnectionState.Unreachable(INTERNAL_ERROR, null)
            throw error
        } finally {
            if (transport === client) transport = null
            withContext(NonCancellable + io) { client.close() }
        }
    }

    /** One connect and stream; returns the error code that ended it. */
    private suspend fun attempt(client: T3Transport, backoff: Backoff): String {
        var connectedAt: Long? = null
        try {
            if (_state.value !is ConnectionState.Connected) _state.value = ConnectionState.Connecting
            val descriptor = ServerDescriptor.from(client.fetchDescriptor(baseUrl, serverEnvironmentId))
            val secret = token() ?: return MISSING_TOKEN
            client.connect(ConnectRoute.Bearer(baseUrl, secret))
            if (shellLock.withLock { held } == null) publish(ShellReducer.parse(client.shell()))
            if (serverEnvironmentId == null) serverEnvironmentId = descriptor.environmentId
            _state.value = ConnectionState.Connected(descriptor)
            try {
                onConnected(descriptor)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }

            var closedWith: String? = null
            val after = shellLock.withLock { cursor }
            client.shellStream(afterSequence = after).collect { update ->
                if (connectedAt == null) connectedAt = clock()
                shellLock.withLock {
                    held = ShellReducer.reduce(held, update)
                    cursor = update.shellCursor(cursor)
                    if (update.repaint || update.closed) showHeld()
                }
                if (update.closed) closedWith = update.error ?: CANCELLED
            }
            return closedWith ?: STREAM_CLOSED
        } catch (error: T3Exception) {
            return error.code
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return INTERNAL_ERROR
        } finally {
            connectedAt?.let { backoff.connectionLasted(clock() - it) }
        }
    }

    /** Replaces the held shell with one read over HTTP, and resumes after its sequence. */
    private suspend fun publish(fresh: ShellState) = shellLock.withLock {
        held = fresh
        cursor = fresh.sequence
        showHeld()
    }

    /** Publishes [held] and persists [cursor] when it moved. Call with [shellLock] held. */
    private suspend fun showHeld() {
        _shell.value = held
        val at = cursor ?: return
        if (at != persistedCursor) {
            cursorStore.setCursor(environmentId, at)
            persistedCursor = at
        }
    }

    /** Drops the held shell and cursor so the next stream starts from a fresh snapshot. */
    private suspend fun reset() {
        shellLock.withLock {
            held = null
            cursor = null
            _shell.value = null
            persistedCursor = null
        }
        cursorStore.setCursor(environmentId, null)
    }

    companion object {
        const val UNAUTHORIZED = "unauthorized"
        const val STREAM_CLOSED = "stream_closed"

        /** Not a lib code: the environment has no stored token. */
        const val MISSING_TOKEN = "missing_token"

        /** How long a cancelled stream waits before it is reopened. */
        const val CANCEL_REOPEN_MS = 250L

        /** Not a lib code: a stream closed with no error (a cancel); never shown. */
        private const val CANCELLED = "cancelled"

        /** Not a lib code: something other than a [T3Exception] failed. */
        const val INTERNAL_ERROR = "internal_error"

        /** Errors after which the stream resumes from the held shell's sequence. */
        val TRANSIENT = setOf("stream_timeout", "disconnected", "rpc_connection_failed", "unreachable")

        /** Errors that retrying cannot fix. */
        val FATAL = setOf("invalid_endpoint", "t3_environment_mismatch", "invalid_connection_options", MISSING_TOKEN)
    }
}
