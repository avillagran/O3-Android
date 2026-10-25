package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.connection.Backoff
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /** The stream ended in a way that may have lost events; a fresh snapshot is on its way. */
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
 *
 * Every stream item goes through [ThreadProjectionShim], which applies, ignores or skips it
 * and always moves the cursor: an event never causes a refetch. A fresh `boundedThread` is
 * read only when the stream ends with [REFETCH_ERRORS] (`stream_backpressure`,
 * `stream_closed`) or a snapshot is malformed, and at most once per [refetchIntervalMs] (a
 * later one waits for the rest of the interval); meanwhile the view stays on screen, marked
 * stale. Transient errors resume from the cursor after a jittered [Backoff]; [FATAL] errors
 * wait for [retry]; any other error backs off and resumes from the cursor too.
 *
 * A stream that ends with no error was cancelled (its handle was connected again or
 * dropped): that is not a failure, so it reopens from the cursor after [CANCEL_REOPEN_MS],
 * with no snapshot and no stale mark. After [MAX_QUICK_CANCELS] such reopens in a row, each
 * cancelled within [QUICK_CANCEL_MS], it backs off instead, so a handle that keeps
 * cancelling cannot spin. When the connection goes away the session simply stops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadSession(
    val threadId: String,
    private val connection: Flow<Connection?>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    /** The shortest time between two `boundedThread` reads. */
    private val refetchIntervalMs: Long = REFETCH_INTERVAL_MS,
    /** Where items are reduced; snapshots can be large, so not the main thread. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _view = MutableStateFlow(ThreadView(threadId))
    val view: StateFlow<ThreadView> = _view.asStateFlow()

    private val shim = ThreadProjectionShim()
    private var needSnapshot = true
    private var stale = false

    /** When the last `boundedThread` read started, for the rate limit. */
    private var lastSnapshotAt: Long? = null
    private var job: Job? = null
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    /** The held projection; for callers that need a field the view does not carry. */
    val projection: org.json.JSONObject? get() = shim.projection

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

    /** Skips a pending backoff or refetch wait, or tries again after a fatal error. */
    fun retry() {
        retryNow.trySend(Unit)
    }

    private suspend fun follow(transport: T3Transport) {
        val backoff = Backoff(random)
        var quickCancels = 0
        while (true) {
            val openedAt = clock()
            var error = attempt(transport, backoff)
            currentCoroutineContext().ensureActive()
            if (error == CANCELLED) {
                quickCancels = if (clock() - openedAt < QUICK_CANCEL_MS) quickCancels + 1 else 1
                if (quickCancels <= MAX_QUICK_CANCELS) {
                    delay(CANCEL_REOPEN_MS)
                    continue
                }
                error = DISCONNECTED
            } else {
                quickCancels = 0
            }
            when (error) {
                // A snapshot item the shim could not use: read a fresh one, no backoff.
                REFETCH -> {
                    markStale()
                    continue
                }
                in FATAL -> {
                    setConnection(ThreadConnection.Failed(error))
                    while (retryNow.tryReceive().isSuccess) Unit
                    retryNow.receive()
                    needSnapshot = true
                    lastSnapshotAt = null
                    backoff.reset()
                    continue
                }
                in REFETCH_ERRORS, MALFORMED_SNAPSHOT -> markStale()
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
            if (needSnapshot || !shim.hasSnapshot) {
                awaitRefetchSlot()
                if (!shim.hasSnapshot) setConnection(ThreadConnection.Loading)
                lastSnapshotAt = clock()
                val snapshot = transport.boundedThread(threadId)
                if (!shim.replace(snapshot)) return MALFORMED_SNAPSHOT
                needSnapshot = false
                stale = false
                publish()
            }
            var ended: String? = null
            transport.threadStream(
                threadId,
                afterSequence = shim.sequence,
                completionMarker = false,
                boundedSnapshot = true,
                compactTurnItems = false,
            ).takeWhile { update ->
                if (connectedAt == null) {
                    connectedAt = clock()
                    setConnection(ThreadConnection.Live)
                }
                val items = update.batch.items
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    when (shim.apply(item)) {
                        ApplyResult.MALFORMED -> ended = REFETCH
                        // A stream snapshot replaced everything: no longer stale.
                        ApplyResult.APPLIED -> if (item.optString("kind") == "snapshot") stale = false
                        ApplyResult.IGNORED, ApplyResult.UNKNOWN -> Unit
                    }
                    if (ended != null) break
                }
                if (update.closed && ended == null) ended = update.error ?: CANCELLED
                if (update.repaint || update.closed || ended != null) publish()
                ended == null
            }.collect()
            return ended ?: ConnectionSupervisor.STREAM_CLOSED
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

    /**
     * Waits until [refetchIntervalMs] has passed since the last `boundedThread` read (the
     * trailing edge of the rate limit), or until [retry].
     */
    private suspend fun awaitRefetchSlot() {
        val last = lastSnapshotAt ?: return
        val wait = last + refetchIntervalMs - clock()
        if (wait <= 0) return
        while (retryNow.tryReceive().isSuccess) Unit
        withTimeoutOrNull(wait) { retryNow.receive() }
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
        /** Not a lib code: a stream snapshot was malformed, so the session reads a fresh one. */
        const val REFETCH = "refetch"

        /** Not a lib code: the shared client was closed under a call. */
        const val DISCONNECTED = "disconnected"

        /** Not a lib code: the stream closed with no error (a cancel); never shown. */
        private const val CANCELLED = "cancelled"

        /** How long a cancelled stream waits before it is reopened. */
        const val CANCEL_REOPEN_MS = 250L

        /** A cancel this soon after the stream opened counts towards [MAX_QUICK_CANCELS]. */
        const val QUICK_CANCEL_MS = 5_000L

        /** Quick cancels in a row that reopen at once; the next one backs off. */
        const val MAX_QUICK_CANCELS = 3

        /** Not a lib code: `boundedThread` answered without a `projection` or `snapshotSequence`. */
        const val MALFORMED_SNAPSHOT = "malformed_snapshot"

        /** The shortest time between two `boundedThread` reads. */
        const val REFETCH_INTERVAL_MS = 10_000L

        /** Stream ends after which events may have been lost: the snapshot is read again. */
        val REFETCH_ERRORS = setOf("stream_backpressure", ConnectionSupervisor.STREAM_CLOSED)

        /** Errors after which only [retry] (or a new connection) tries again. */
        val FATAL = setOf("unauthorized", "invalid_thread_id", "http_404")
    }
}
