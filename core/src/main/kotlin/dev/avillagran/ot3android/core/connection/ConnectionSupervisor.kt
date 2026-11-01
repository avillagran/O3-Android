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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** Connection state of one environment, as the UI shows it. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState

    /**
     * Authenticated; the shell stream is open or being resumed. With [viaFallback] it runs
     * over the catalog's route because the environment's own URL is out of reach.
     */
    data class Connected(val descriptor: ServerDescriptor, val viaFallback: Boolean = false) : ConnectionState

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
 * The supervisor's live connection, for code that opens its own streams on it (the thread
 * view). [epoch] grows with every `connect` of [transport]; a `connect` ends every open
 * stream of the handle as a cancel, so a holder reopens its streams whenever the epoch
 * changes, and stops them while there is no connection. Never close [transport]: the
 * supervisor owns it.
 */
data class Connection(val transport: T3Transport, val epoch: Int)

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
 * `repaint`, never mid-chunk. The cursor is written to [cursorStore] off the hot path:
 * conflated, [PERSIST_DEBOUNCE_MS] after it last moved, and once more when a run ends. It
 * is not read back yet: without a cached shell, a run always starts from a shell GET and
 * resumes after its sequence (docs/DECISIONS.md).
 *
 * An environment may have a fallback in [routeFallbacks]: the bridge catalog's route to the
 * same server, for a row saved with a URL that only works at home. When the row's own URL is
 * out of reach ([ROUTE_ERRORS]) the next attempt, in the same backoff step, uses the fallback.
 * Its descriptor is checked against the known environment id before the token is sent; the
 * row itself never changes. While the fallback holds, the own URL is probed with no
 * credential on a jittered backoff, and on [wake]. When it answers, the run goes back,
 * resuming from the held cursor: both URLs reach one server. A fallback whose server refuses
 * it is not tried again in that run, and its refusal is not the row's: the row reports its
 * own error and keeps retrying.
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
    /** Where a shell read over HTTP is parsed (it can hold hundreds of threads). */
    private val computation: CoroutineDispatcher = Dispatchers.Default,
    /** Fallback route by local environment id; see the class doc. */
    private val routeFallbacks: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap()),
) {
    val environmentId: String = environment.id
    private val baseUrl = environment.baseUrl

    /** The server's environment id, checked by `fetchDescriptor` once known. */
    @Volatile private var serverEnvironmentId: String? = environment.environmentId

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _connection = MutableStateFlow<Connection?>(null)

    /**
     * The transport while [state] is [ConnectionState.Connected] (token proven, shell
     * stream opening); null while connecting, backing off, stopped or failed.
     */
    val connection: StateFlow<Connection?> = _connection.asStateFlow()

    /** Successful `connect` calls so far, across runs; see [Connection.epoch]. */
    private var epoch = 0

    private val _shell = MutableStateFlow<ShellState?>(null)

    /**
     * The reduced shell, null until the first snapshot. It stays visible while the supervisor
     * reconnects, stops or resets, until a newer shell replaces it.
     */
    val shell: StateFlow<ShellState?> = _shell.asStateFlow()

    /** Display-only disk shell. A fresh GET still establishes the live stream cursor. */
    internal fun showCachedShell(cached: ShellState) {
        if (_shell.value == null) _shell.value = cached
    }

    /** The shell as applied so far; [shell] catches up with it on repaint. */
    private var held: ShellState? = null

    /** Where the shell stream resumes (`afterSequence`) for [held]; see `shellCursor`. */
    private var cursor: Long? = null
    private val shellLock = Mutex()

    /** The cursor to write next; [persistCursor] writes it when it differs from the last write. */
    private val pendingCursor = MutableStateFlow<Long?>(null)
    private var persistedCursor: Long? = null
    private val persistLock = Mutex()

    /** Guards [generation], [job], [transport], [epoch] and every write of [state] and [connection]. */
    private val lock = Any()

    /**
     * Bumped by every [start] and [stop]. A run writes [state] only while its generation is
     * current, so a run that is still winding down after [stop] cannot overwrite what the
     * next run (or the stop itself) published.
     */
    private var generation = 0

    @Volatile private var transport: T3Transport? = null
    @Volatile private var job: Job? = null
    private val retryNow = Channel<Unit>(Channel.CONFLATED)
    private val probeNow = Channel<Unit>(Channel.CONFLATED)

    val isRunning: Boolean get() = job?.isActive == true

    /** Starts connecting unless already running. */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            val run = ++generation
            job = scope.launch { run(run) }
        }
    }

    /** Stops and closes the transport. The shell stays visible and resumes on [start]. */
    fun stop() {
        stopRun()
    }

    /** [stop], then waits until the run has closed its transport and written its cursor. */
    suspend fun stopAndJoin() {
        stopRun()?.join()
    }

    private fun stopRun(): Job? = synchronized(lock) {
        val stopped = job
        job = null
        generation++
        stopped?.cancel()
        _state.value = ConnectionState.Disconnected
        _connection.value = null
        stopped
    }

    /** Publishes [value] if [run] is still the current run. */
    private fun setState(run: Int, value: ConnectionState) = synchronized(lock) {
        if (run == generation) _state.value = value
    }

    /** Publishes [ConnectionState.Connected] and its [Connection] together, if [run] is current. */
    private fun setConnected(run: Int, descriptor: ServerDescriptor, client: T3Transport, epoch: Int, viaFallback: Boolean) = synchronized(lock) {
        if (run != generation) return@synchronized
        _state.value = ConnectionState.Connected(descriptor, viaFallback)
        _connection.value = Connection(client, epoch)
    }

    private fun clearConnection(run: Int) = synchronized(lock) {
        if (run == generation) _connection.value = null
    }

    /** Retries at once: skips a pending backoff, or starts again after `unauthorized` or a fatal error. */
    fun retry() {
        if (isRunning) retryNow.trySend(Unit) else start()
    }

    /**
     * Probes the open streams (on resume or a network change; a dead socket then ends with
     * `stream_timeout` within about 3 s), skips a pending backoff and, on a fallback route,
     * checks the environment's own URL again.
     */
    suspend fun wake() {
        runCatching { transport?.wakeSubscriptions() }
        probeNow.trySend(Unit)
        if (_state.value is ConnectionState.Unreachable && isRunning) retryNow.trySend(Unit)
    }

    /**
     * Replaces the shell with a fresh shell GET (pull to refresh), unless the stream has
     * already moved past it. Fails with the lib's `T3Exception` when not connected or the
     * request fails. Safe to call from the main thread.
     */
    suspend fun refresh() {
        val current = transport
        if (current == null || _state.value !is ConnectionState.Connected) {
            retry()
            throw T3Exception("not_connected")
        }
        val fresh = withContext(computation) { ShellReducer.parse(current.shell()) }
        shellLock.withLock {
            val heldSequence = held?.sequence
            val freshSequence = fresh.sequence
            if (heldSequence == null || freshSequence == null || freshSequence >= heldSequence) replaceHeld(fresh)
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun run(run: Int) {
        val backoff = Backoff(random)
        val client = transportFactory.create()
        synchronized(lock) { if (run == generation) transport = client }
        try {
            coroutineScope {
                val persister = launch {
                    pendingCursor.debounce(PERSIST_DEBOUNCE_MS).collect { persistCursor() }
                }
                // A fallback that turns up during a long backoff should not wait it out.
                val fallbackWatch = launch {
                    routeFallbacks.map { it[environmentId] }.distinctUntilChanged().drop(1).collect { retryNow.trySend(Unit) }
                }
                reconnect(run, client, backoff)
                persister.cancel()
                fallbackWatch.cancel()
            }
        } catch (error: Exception) {
            // Never end silently: the state says so, retry() starts over, and the scope's
            // exception handler logs the class.
            if (error !is CancellationException) setState(run, ConnectionState.Unreachable(INTERNAL_ERROR, null))
            throw error
        } finally {
            synchronized(lock) {
                if (transport === client) transport = null
                // Ended while current (the scope was cancelled, not stop()): not connected any more.
                val live = _state.value is ConnectionState.Connected || _state.value == ConnectionState.Connecting
                if (run == generation && live) _state.value = ConnectionState.Disconnected
                if (run == generation) _connection.value = null
            }
            withContext(NonCancellable + io) {
                client.close()
                persistCursor()
            }
        }
    }

    /** The route of one run. */
    private class RouteChoice {
        /** The fallback in use; null for the environment's own URL. */
        var fallback: String? = null

        /** A fallback whose server refused it: not tried again in this run. */
        var refused: String? = null

        /** What the own URL last failed with, the error to show while a fallback fails too. */
        var ownError: String? = null

        /** The current route reached [ConnectionState.Connected] in the latest attempt. */
        var connected = false

        fun fallbackFor(error: String, candidate: String?): String? =
            candidate.takeIf { error in ROUTE_ERRORS && it != refused }
    }

    /** Connects and follows the stream until `unauthorized` or a fatal error. */
    private suspend fun reconnect(run: Int, client: T3Transport, backoff: Backoff) {
        val routes = RouteChoice()
        val probes = Backoff(random, PROBE_FIRST_MS, PROBE_MAX_MS)
        while (true) {
            routes.connected = false
            val fallback = routes.fallback
            val error = attempt(run, client, backoff, routes, probes)
            currentCoroutineContext().ensureActive()
            if (error == CANCELLED) {
                // Still running, so not our stop(): reopen from the held cursor. The
                // fixed pause keeps a handle that cancels at once from spinning.
                delay(CANCEL_REOPEN_MS)
                continue
            }
            if (error == ROUTE_CHANGED) {
                routes.fallback = null
                continue
            }
            if (fallback == null) {
                routes.ownError = error
                val next = routes.fallbackFor(error, routeFallbacks.value[environmentId])
                if (next != null && serverEnvironmentId != null) {
                    // Same backoff step: it is paid only if this route fails as well.
                    routes.fallback = next
                    continue
                }
            } else if (error != MISSING_TOKEN && (error == UNAUTHORIZED || error in FATAL)) {
                routes.refused = fallback
                routes.fallback = null
                pause(run, backoff, routes.ownError ?: error)
                continue
            } else if (!routes.connected) {
                routes.fallback = null
                pause(run, backoff, routes.ownError ?: error)
                continue
            }
            when {
                error == UNAUTHORIZED -> {
                    setState(run, ConnectionState.Unauthorized)
                    return
                }
                error in FATAL -> {
                    setState(run, ConnectionState.Unreachable(error, nextRetryAt = null))
                    return
                }
                error !in TRANSIENT -> reset()
            }
            pause(run, backoff, error)
        }
    }

    /** Publishes [error] and waits out one jittered backoff step, or a [retry]. */
    private suspend fun pause(run: Int, backoff: Backoff, error: String) {
        val delayMs = backoff.nextDelayMs()
        setState(run, ConnectionState.Unreachable(error, clock() + delayMs))
        while (retryNow.tryReceive().isSuccess) Unit
        withTimeoutOrNull(delayMs) { retryNow.receive() }
    }

    /** Runs [stream] until it ends, the own URL answers again or [fallback] is no longer offered. */
    private suspend fun untilRouteChanges(fallback: String, probes: Backoff, stream: suspend () -> String): String = coroutineScope {
        val following = async { stream() }
        val withdrawn = async {
            routeFallbacks.first { it[environmentId] != fallback }
            ROUTE_CHANGED
        }
        val returned = async {
            while (true) {
                withTimeoutOrNull(probes.nextDelayMs()) { probeNow.receive() }
                if (ownRouteAnswers()) break
            }
            ROUTE_CHANGED
        }
        select {
            following.onAwait { it }
            withdrawn.onAwait { it }
            returned.onAwait { it }
        }.also { coroutineContext.cancelChildren() }
    }

    /** A credential-free look at the environment's own URL, on a transport of its own. */
    private suspend fun ownRouteAnswers(): Boolean {
        val probe = withContext(io) { transportFactory.create() }
        return try {
            probe.fetchDescriptor(baseUrl, serverEnvironmentId)
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        } finally {
            withContext(NonCancellable + io) { probe.close() }
        }
    }

    /** One connect and stream; returns the error code that ended it. */
    private suspend fun attempt(run: Int, client: T3Transport, backoff: Backoff, routes: RouteChoice, probes: Backoff): String {
        val fallback = routes.fallback
        val url = fallback ?: baseUrl
        var connectedAt: Long? = null
        try {
            if (_state.value !is ConnectionState.Connected) setState(run, ConnectionState.Connecting)
            val descriptor = ServerDescriptor.from(client.fetchDescriptor(url, serverEnvironmentId))
            val secret = token() ?: return MISSING_TOKEN
            client.connect(ConnectRoute.Bearer(url, secret))
            val connected = synchronized(lock) { ++epoch }
            if (shellLock.withLock { held } == null) {
                val fresh = withContext(computation) { ShellReducer.parse(client.shell()) }
                shellLock.withLock { replaceHeld(fresh) }
            }
            if (serverEnvironmentId == null) serverEnvironmentId = descriptor.environmentId
            setConnected(run, descriptor, client, connected, viaFallback = fallback != null)
            routes.connected = true
            try {
                onConnected(descriptor)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }

            val after = shellLock.withLock { cursor }
            val follow: suspend () -> String = {
                var closedWith: String? = null
                client.shellStream(afterSequence = after).collect { update ->
                    if (connectedAt == null) connectedAt = clock()
                    shellLock.withLock {
                        held = ShellReducer.reduce(held, update)
                        cursor = update.shellCursor(cursor)
                        if (update.repaint || update.closed) showHeld()
                    }
                    if (update.closed) closedWith = update.error ?: CANCELLED
                }
                closedWith ?: STREAM_CLOSED
            }
            return if (fallback == null) follow() else untilRouteChanges(fallback, probes, follow)
        } catch (error: T3Exception) {
            return error.code
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return INTERNAL_ERROR
        } finally {
            clearConnection(run)
            connectedAt?.let {
                backoff.connectionLasted(clock() - it)
                if (fallback == null) probes.connectionLasted(clock() - it)
            }
        }
    }

    /** Replaces the held shell with one read over HTTP, to resume after its sequence. Call with [shellLock] held. */
    private fun replaceHeld(fresh: ShellState) {
        held = fresh
        cursor = fresh.sequence
        showHeld()
    }

    /** Publishes [held] and queues [cursor] for [persistCursor]. Call with [shellLock] held. */
    private fun showHeld() {
        _shell.value = held
        pendingCursor.value = cursor
    }

    /**
     * Drops the held shell and cursor so the next stream starts from a fresh snapshot. [shell]
     * keeps showing the last one meanwhile, so the sidebar does not blank on an `http_5xx`.
     */
    private suspend fun reset() {
        shellLock.withLock {
            held = null
            cursor = null
            pendingCursor.value = null
        }
    }

    /**
     * Writes [pendingCursor] unless it is what was written last. A failed write only loses a
     * resume point, so it is not an error of the connection.
     */
    private suspend fun persistCursor() = persistLock.withLock {
        val at = pendingCursor.value
        if (at == persistedCursor) return@withLock
        try {
            cursorStore.setCursor(environmentId, at)
            persistedCursor = at
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }

    companion object {
        const val UNAUTHORIZED = "unauthorized"
        const val STREAM_CLOSED = "stream_closed"

        /** Not a lib code: the environment has no stored token. */
        const val MISSING_TOKEN = "missing_token"

        /** How long the cursor must stay put before it is written. */
        const val PERSIST_DEBOUNCE_MS = 1_500L

        /** How long a cancelled stream waits before it is reopened. */
        const val CANCEL_REOPEN_MS = 250L

        /** Not a lib code: a stream closed with no error (a cancel); never shown. */
        private const val CANCELLED = "cancelled"

        /** Not a lib code: the route changed under a healthy stream; never shown. */
        private const val ROUTE_CHANGED = "route_changed"

        /** How soon the own URL is first probed from a fallback, and how far that backs off. */
        const val PROBE_FIRST_MS = 15_000L
        const val PROBE_MAX_MS = 120_000L

        /** Not a lib code: something other than a [T3Exception] failed. */
        const val INTERNAL_ERROR = "internal_error"

        /** Errors after which the stream resumes from the held shell's sequence. */
        val TRANSIENT = setOf("stream_timeout", "disconnected", "rpc_connection_failed", "unreachable", "subscription_limit")

        /** Errors that say the route is out of reach, not that the server refused. */
        val ROUTE_ERRORS = setOf("stream_timeout", "disconnected", "rpc_connection_failed", "unreachable")

        /** Errors that retrying cannot fix. */
        val FATAL = setOf("invalid_endpoint", "t3_environment_mismatch", "invalid_connection_options", MISSING_TOKEN)
    }
}
