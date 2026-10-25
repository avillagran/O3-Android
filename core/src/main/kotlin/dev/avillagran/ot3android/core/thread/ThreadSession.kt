package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.connection.Backoff
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.random.Random

/** Where the thread's own stream stands; the environment's connection is shown separately. */
sealed interface ThreadConnection {
    /** The environment is not connected; the session starts when it is. */
    data object Waiting : ThreadConnection

    /** Reading the bounded snapshot or opening the stream. */
    data object Loading : ThreadConnection

    /** The stream is open. */
    data object Live : ThreadConnection

    /** The stream ended with the lib's [error]; the next attempt runs at [nextRetryAt]. */
    data class Retrying(val error: String, val nextRetryAt: Long) : ThreadConnection

    /** Retrying cannot fix [error] (no such thread, token rejected); waits for [ThreadSession.retry]. */
    data class Failed(val error: String) : ThreadConnection
}

/** The open thread as the screen shows it. */
data class ThreadView(
    val threadId: String,
    /** A snapshot has been applied at least once. */
    val loaded: Boolean = false,
    val title: String? = null,
    val messages: List<ThreadMessage> = emptyList(),
    val working: Boolean = false,
    /** The run an interrupt targets while [working]. */
    val activeRunId: String? = null,
    val pendingRequests: List<RuntimeRequest> = emptyList(),
    /** The thread's `modelSelection` as compact JSON, sent with each message. */
    val modelSelection: String? = null,
    val connectionState: ThreadConnection = ThreadConnection.Waiting,
    /** An event was not applied; a fresh snapshot is on its way. */
    val stale: Boolean = false,
    val sequence: Long? = null,
)

/**
 * One open thread: reads its bounded snapshot, follows its stream from the snapshot's
 * sequence and publishes [view] on each `repaint`, never mid-chunk.
 *
 * It runs on the environment's [connection] (`ConnectionSupervisor.connection`) and stops
 * whenever that goes null. A new [Connection] (a reconnect, or a new epoch of the same
 * transport, whose `connect` ended every open stream) restarts the stream with a fresh
 * backoff; the held projection and its sequence survive, so it resumes where it was.
 * Ending streams follow the supervisor's rules: transient errors resume from the cursor after
 * a jittered [Backoff]; [FATAL] errors wait for [retry]; anything else (`stream_closed`,
 * `stream_backpressure`, ...) keeps the view on screen, marks it stale and refetches the
 * snapshot. An event [ThreadProjectionShim] does not apply marks the view stale and, after
 * [refetchDebounceMs], replaces it with a fresh `boundedThread` and a stream from its sequence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadSession(
    val threadId: String,
    private val connection: Flow<Connection?>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val refetchDebounceMs: Long = 500,
    /** Where items are reduced; snapshots can be large, so not the main thread. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _view = MutableStateFlow(ThreadView(threadId))
    val view: StateFlow<ThreadView> = _view.asStateFlow()

    private val shim = ThreadProjectionShim()
    private var needSnapshot = true
    private var stale = false
    private var job: Job? = null
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    /** The held projection; for callers that need a field the view does not carry. */
    val projection: JSONObject? get() = shim.projection

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(dispatcher) {
            connection.collectLatest { current ->
                if (current == null) {
                    setConnection(ThreadConnection.Waiting)
                } else {
                    follow(current.transport)
                }
            }
        }
    }

    @Synchronized
    fun close() {
        job?.cancel()
        job = null
    }

    /** Skips a pending backoff, or tries again after a fatal error. */
    fun retry() {
        retryNow.trySend(Unit)
    }

    private suspend fun follow(transport: T3Transport) {
        val backoff = Backoff(random)
        while (true) {
            val error = attempt(transport, backoff)
            currentCoroutineContext().ensureActive()
            when {
                error == REFETCH -> {
                    needSnapshot = true
                    continue
                }
                error in FATAL -> {
                    setConnection(ThreadConnection.Failed(error))
                    while (retryNow.tryReceive().isSuccess) Unit
                    retryNow.receive()
                    needSnapshot = true
                    backoff.reset()
                    continue
                }
                error !in ConnectionSupervisor.TRANSIENT -> markStale()
            }
            val delayMs = backoff.nextDelayMs()
            setConnection(ThreadConnection.Retrying(error, clock() + delayMs))
            while (retryNow.tryReceive().isSuccess) Unit
            withTimeoutOrNull(delayMs) { retryNow.receive() }
        }
    }

    /** One snapshot (when needed) and stream; returns the error code that ended it, or [REFETCH]. */
    private suspend fun attempt(transport: T3Transport, backoff: Backoff): String {
        var connectedAt: Long? = null
        try {
            if (needSnapshot || stale || !shim.hasSnapshot) {
                if (!shim.hasSnapshot) setConnection(ThreadConnection.Loading)
                val snapshot = transport.boundedThread(threadId)
                if (!shim.replace(snapshot)) return MALFORMED_SNAPSHOT
                needSnapshot = false
                stale = false
                publish()
            }
            return coroutineScope {
                val outer = this
                val refetch = CompletableDeferred<Unit>()
                var timer: Job? = null
                val stream = async {
                    var closedWith: String? = null
                    transport.threadStream(
                        threadId,
                        afterSequence = shim.sequence,
                        completionMarker = false,
                        boundedSnapshot = true,
                        compactTurnItems = false,
                    ).collect { update ->
                        if (connectedAt == null) {
                            connectedAt = clock()
                            setConnection(ThreadConnection.Live)
                        }
                        val items = update.batch.items
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            when (shim.apply(item)) {
                                ApplyResult.REFETCH -> {
                                    stale = true
                                    if (timer == null) timer = outer.launch {
                                        delay(refetchDebounceMs)
                                        refetch.complete(Unit)
                                    }
                                }
                                // A stream snapshot replaced everything: no longer stale.
                                ApplyResult.APPLIED -> if (item.optString("kind") == "snapshot") stale = false
                                ApplyResult.IGNORED -> Unit
                            }
                        }
                        if (update.closed) closedWith = update.error ?: ConnectionSupervisor.STREAM_CLOSED
                        if (update.repaint || update.closed) {
                            publish()
                        } else if (stale && !_view.value.stale) {
                            // Show the stale chip at once, but no half-applied chunk.
                            _view.update { it.copy(stale = true) }
                        }
                    }
                    closedWith ?: ConnectionSupervisor.STREAM_CLOSED
                }
                val ended = select {
                    stream.onAwait { it }
                    refetch.onAwait { REFETCH }
                }
                stream.cancel()
                timer?.cancel()
                ended
            }
        } catch (error: T3Exception) {
            return error.code
        } catch (error: IllegalStateException) {
            // The supervisor closed its client under a call (`client_closed`): it is reconnecting.
            if (error is CancellationException) throw error
            return DISCONNECTED
        } finally {
            connectedAt?.let { backoff.connectionLasted(clock() - it) }
        }
    }

    private fun markStale() {
        if (shim.hasSnapshot) {
            stale = true
            publish()
        }
        needSnapshot = true
    }

    private fun setConnection(state: ThreadConnection) {
        _view.update { it.copy(connectionState = state) }
    }

    private fun publish() {
        val p = shim.projection
        _view.update { current ->
            current.copy(
                loaded = p != null,
                title = ThreadProjectionShim.title(p),
                messages = ThreadProjectionShim.messages(p),
                working = ThreadProjectionShim.working(p),
                activeRunId = ThreadProjectionShim.interruptibleRunId(p),
                pendingRequests = ThreadProjectionShim.pendingRequests(p),
                modelSelection = ThreadProjectionShim.modelSelection(p)?.toString(),
                stale = stale,
                sequence = shim.sequence,
            )
        }
    }

    companion object {
        /** Not a lib code: the session ended a stream itself to read a fresh snapshot. */
        const val REFETCH = "refetch"

        /** Not a lib code: the shared client was closed under a call. */
        const val DISCONNECTED = "disconnected"

        /** Not a lib code: `boundedThread` answered without a `projection`. */
        const val MALFORMED_SNAPSHOT = "malformed_snapshot"

        /** Errors after which only [retry] (or a new connection) tries again. */
        val FATAL = setOf("unauthorized", "invalid_thread_id", "http_404", MALFORMED_SNAPSHOT)
    }
}
