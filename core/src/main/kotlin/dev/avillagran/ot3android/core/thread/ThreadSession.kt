package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.connection.Backoff
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import dev.avillagran.t3.ThreadUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    /** The user and assistant rows of [timeline], as bubbles. */
    val messages: List<ThreadMessage> = emptyList(),
    /** `threadState.working` of [timeline]. */
    val working: Boolean = false,
    /** The run an interrupt targets while [working] (`threadState.activeRunId`). */
    val activeRunId: String? = null,
    /** The lib's `approvals` and `questions`, for the banners. */
    val pendingRequests: List<RuntimeRequest> = emptyList(),
    /**
     * The whole `timeline.view` of the held projection (`timeline` rows with `rev`,
     * `threadState`, `activity`, `tasks`, `queue`, `planView`, ...), for UI-2. Null until loaded.
     */
    val timeline: JSONObject? = null,
    /** The thread's `modelSelection` as compact JSON, sent with each message. */
    val modelSelection: String? = null,
    val runtimeMode: String? = null,
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
 * The session holds the projection as the server's JSON. A snapshot (the `boundedThread`
 * body or a stream `snapshot`) replaces it; each update's events go through the lib's
 * `projection.apply` in one call, and on each repaint the lib's `timeline.view` builds
 * [ThreadView] from it, both on [domain]'s worker. The lib ignores event types it does not
 * know and its cursor ([ThreadUpdate.cursor]) always moves on, so an event never causes a
 * refetch. A fresh `boundedThread` is read only when the stream ends with [REFETCH_ERRORS]
 * (`stream_backpressure`, `stream_closed`), a snapshot is malformed or the lib cannot read
 * the projection, and at most once per [refetchIntervalMs] (a later one waits for the rest
 * of the interval); meanwhile the view stays on screen, marked stale. Transient errors resume from the cursor after a jittered [Backoff]; [FATAL] errors
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
    /** The lib's `projection.apply` and `timeline.view`; never on the main thread. */
    private val domain: DomainCalls = DomainEngine.shared,
    private val cache: ThreadCache? = null,
    private val cacheEnvironment: String? = null,
    private val cacheAuthority: suspend () -> String? = { null },
    private val cacheAuthorityUpdates: Flow<String?>? = null,
) {
    private val _view = MutableStateFlow(ThreadView(threadId))
    val view: StateFlow<ThreadView> = _view.asStateFlow()

    /** The held projection, its stream sequence and its bounded-history fields. */
    @Volatile private var projection: JSONObject? = null

    /** Defensive snapshot for native mutation validation; stream-owned objects stay private. */
    fun projectionSnapshot(): JSONObject? = projection?.let { JSONObject(it.toString()) }
    private val cacheEnabled get() = cache != null && cacheEnvironment != null
    private var projectionAuthority: String? = null
    private var cacheLease: ThreadCache.Lease? = null
    private var cacheDirty = false
    private var lastCacheWriteAt: Long? = null
    private var sequence: Long? = null
    private var history = ThreadTimelines.History.NONE

    /** The projection changed since the last [publish]. */
    private var dirty = false
    private var needSnapshot = true
    private var stale = false

    /** When the last `boundedThread` read started, for the rate limit. */
    private var lastSnapshotAt: Long? = null
    private var job: Job? = null
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        val previous = job
        job = scope.launch(dispatcher) {
            previous?.join() // A prior close may still be flushing/closing its cache lease.
            var initialized = false
            var lastConnection: Connection? = null
            var blockedConnection: Connection? = null
            try {
                // Credential withdrawal must be observed even while the connection stays null.
                combine(connection, cacheAuthorityUpdates ?: flowOf(null)) { current, _ -> current }
                    .collectLatest { current ->
                    if (cacheEnabled) {
                        val authority = cacheAuthority()
                        if (!initialized || projectionAuthority != authority) {
                            if (projectionAuthority != authority) {
                                // A previously observed transport belongs to the old local owner.
                                // Do not persist its projection under a new credential's digest.
                                if (initialized) blockedConnection = lastConnection
                                clearCachedProjection()
                            }
                            projectionAuthority = authority
                            initialized = true
                            try { restoreCache(authority) } catch (_: java.io.IOException) {
                                cacheLease?.let { cache?.close(it) }
                                cacheLease = null
                            }
                        }
                        if (current != null) lastConnection = current
                        if (authority == null || (current != null && current == blockedConnection)) {
                            _view.update { it.copy(stale = true, connectionState = ThreadConnection.Waiting) }
                            return@collectLatest
                        }
                    }
                    if (current == null) {
                        if (projection != null) { stale = true; _view.update { it.copy(stale = true) } }
                        setConnection(ThreadConnection.Waiting)
                    } else {
                        follow(current.transport)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    persistCache(force = true)
                    cacheLease?.let { cache?.close(it) }
                    cacheLease = null
                }
            }
        }
    }

    @Synchronized
    fun close() {
        job?.cancel()
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
            // A saved cursor that cannot resume falls back through the existing snapshot path.
            if (stale && error != CANCELLED) needSnapshot = true
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
                // A snapshot item that could not be used, or a projection the lib refused: the held
                // projection cannot be trusted, so read a fresh one (rate-limited), no backoff.
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
            if (needSnapshot || projection == null) {
                awaitRefetchSlot()
                if (projection == null) setConnection(ThreadConnection.Loading)
                lastSnapshotAt = clock()
                val snapshot = transport.boundedThread(threadId)
                if (!replace(snapshot)) return MALFORMED_SNAPSHOT
                needSnapshot = false
                stale = false
                // A fresh snapshot that cannot be shown would fail the same way again.
                if (!publish()) return INTERNAL_ERROR
            }
            var ended: String? = null
            val stream = transport.threadStream(
                threadId,
                afterSequence = sequence,
                completionMarker = false,
                boundedSnapshot = true,
                compactTurnItems = false,
            )
            // An idle server may not acknowledge a saved cursor with an item. Keep the saved
            // view visible, but bound that wait and validate with a snapshot instead of
            // leaving actions disabled until some unrelated event arrives.
            val updates = if (stale) flow {
                coroutineScope {
                    // Idle/cursor-only frames do not validate the saved projection.
                    // Bound the whole stale interval, not just receipt of the first frame.
                    val validation = launch {
                        delay(CACHE_RESUME_TIMEOUT_MS)
                        throw T3Exception(REFETCH)
                    }
                    try { stream.collect { update ->
                        emit(update)
                        if (!stale) validation.cancel()
                    } }
                    finally { validation.cancel() }
                }
            } else stream
            updates.takeWhile { update ->
                if (connectedAt == null) {
                    connectedAt = clock()
                    setConnection(ThreadConnection.Live)
                }
                try {
                    if (!apply(update)) ended = REFETCH
                } catch (_: T3Exception) {
                    // The lib refused the projection or an event: refetch, do not guess.
                    ended = REFETCH
                } catch (error: RuntimeException) {
                    if (error is CancellationException) throw error
                    ended = REFETCH
                }
                if (update.closed && ended == null) ended = update.error ?: CANCELLED
                if (update.repaint || update.closed || ended != null) {
                    // Nothing applied (a cursor-only item, a type the lib ignores): no new view.
                    if (!dirty) {
                        _view.update { it.copy(sequence = sequence) }
                        persistCache()
                    } else if (!publish()) {
                        ended = REFETCH
                    }
                }
                ended == null
            }.collect()
            return ended ?: ConnectionSupervisor.STREAM_CLOSED
        } catch (error: T3Exception) {
            return error.code
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            // The supervisor closed its client under a call: it is reconnecting. Anything
            // else is a bug, not a connection problem.
            return if (error.message in CLOSED) DISCONNECTED else INTERNAL_ERROR
        } catch (_: RuntimeException) {
            return INTERNAL_ERROR
        } finally {
            connectedAt?.let { backoff.connectionLasted(clock() - it) }
        }
    }

    /**
     * Before a refetch (a snapshot is already held), waits until [refetchIntervalMs] has
     * passed since the last `boundedThread` read (the trailing edge of the rate limit), or
     * until [retry]. A first load only waits for the backoff.
     */
    private suspend fun awaitRefetchSlot() {
        if (projection == null) return
        val last = lastSnapshotAt ?: return
        val wait = last + refetchIntervalMs - clock()
        if (wait <= 0) return
        while (retryNow.tryReceive().isSuccess) Unit
        withTimeoutOrNull(wait) { retryNow.receive() }
    }

    private fun markStale() {
        if (projection != null) {
            stale = true
            _view.update { it.copy(stale = true) }
        }
        needSnapshot = true
    }

    /**
     * Holds a snapshot: the `boundedThread` body or a stream `snapshot` item, both
     * `{snapshotSequence, projection, historyCursor?, hasMoreHistory?, ...}`. False, keeping
     * what is held, when either of the first two is missing.
     */
    private fun replace(snapshot: JSONObject): Boolean {
        val next = snapshot.optJSONObject("projection") ?: return false
        val at = (snapshot.opt("snapshotSequence") as? Number)?.toLong() ?: return false
        projection = next
        sequence = at
        history = ThreadTimelines.History.of(snapshot)
        dirty = true
        cacheDirty = true
        return true
    }

    /**
     * One stream update, in the lib's split ([ThreadUpdate.snapshot] then
     * [ThreadUpdate.events]): the snapshot replaces the projection, every `event` item goes
     * through `projection.apply` in one call, and the resume cursor is the lib's. False when
     * a snapshot is malformed or events arrive with no projection to apply them to.
     */
    private suspend fun apply(update: ThreadUpdate): Boolean {
        update.snapshot?.let { snapshot ->
            if (!replace(snapshot)) return false
            // A stream snapshot replaced everything: no longer stale.
            stale = false
        }
        // `unknown-event` items carry no event: only the cursor moves for them.
        val events = update.events.mapNotNull { it.optJSONObject("event") }
        if (events.isNotEmpty()) {
            val held = projection ?: return false
            val result = ThreadTimelines.apply(domain, held, events)
            projection = result.projection
            if (result.applied > 0) dirty = true
        }
        update.cursor?.let { if (sequence != it) cacheDirty = true; sequence = it }
        if (update.start == "resume" && stale) { stale = false; dirty = true }
        if (events.isNotEmpty()) cacheDirty = true
        return true
    }

    private fun setConnection(state: ThreadConnection) {
        _view.update { it.copy(connectionState = state) }
    }

    /**
     * Publishes the held projection through the lib's `timeline.view`; false (the view
     * unchanged) when the lib cannot read it. Title and model are the thread row's own fields.
     */
    private suspend fun publish(): Boolean {
        val p = projection ?: return false
        val next = try {
            val timeline = ThreadTimelines.view(domain, p, history)
            val thread = p.optJSONObject("thread")
            _view.value.copy(
                loaded = true,
                title = thread?.let { if (it.isNull("title")) null else it.optString("title").ifEmpty { null } },
                messages = timeline.messages,
                working = timeline.working,
                activeRunId = timeline.activeRunId,
                pendingRequests = timeline.pendingRequests,
                timeline = timeline.raw,
                modelSelection = thread?.optJSONObject("modelSelection")?.toString(),
                runtimeMode = thread?.optString("runtimeMode")?.takeUnless { it.isBlank() || it == "null" },
                stale = stale,
                sequence = sequence,
            )
        } catch (_: T3Exception) {
            return false
        } catch (error: RuntimeException) {
            if (error is CancellationException) throw error
            return false
        }
        if (cacheEnabled && cacheAuthority() != projectionAuthority) {
            clearCachedProjection()
            return false
        }
        dirty = false
        _view.update { current -> next.copy(connectionState = current.connectionState) }
        persistCache()
        return true
    }

    private suspend fun clearCachedProjection() {
        cacheLease?.let { cache?.remove(it); cache?.close(it) }
        cacheLease = null
        projection = null
        sequence = null
        history = ThreadTimelines.History.NONE
        needSnapshot = true
        dirty = false
        stale = false
        cacheDirty = false
        lastCacheWriteAt = null
        lastSnapshotAt = null
        _view.value = ThreadView(threadId)
    }

    private suspend fun restoreCache(authority: String?) {
        val store = cache ?: return
        val environment = cacheEnvironment ?: return
        if (authority == null) return
        val lease = store.open(environment, threadId, authority) ?: return
        cacheLease = lease
        val saved = store.read(lease) ?: return
        // Disk I/O suspended: do not publish a record after its credential was withdrawn.
        if (cacheAuthority() != authority) { clearCachedProjection(); return }
        if (!replace(saved)) { store.remove(lease); return }
        needSnapshot = saved.optBoolean("cacheWindowed")
        stale = true
        cacheDirty = false
        if (!publish()) {
            store.remove(lease)
            projection = null
            sequence = null
            needSnapshot = true
        }
    }

    private suspend fun persistCache(force: Boolean = false) {
        val lease = cacheLease ?: return
        val p = projection ?: return
        val at = sequence ?: return
        if (cacheAuthority() != lease.authority) return
        if (!cacheDirty || (!force && lastCacheWriteAt?.let { clock() - it < 1_000 } == true)) return
        val snapshot = JSONObject().put("projection", p).put("snapshotSequence", at)
            .put("hasMoreHistory", history.hasMore).put("payloadBudgetExceeded", history.payloadBudgetExceeded)
        history.cursor?.let { snapshot.put("historyCursor", it) }
        try { cache?.write(lease, snapshot); cacheDirty = false; lastCacheWriteAt = clock() }
        catch (error: java.io.IOException) { /* Cache loss only costs a fresh snapshot. */ }
    }

    companion object {
        /** Not a lib code: a stream snapshot was malformed or the lib refused the projection, so the session reads a fresh one. */
        const val REFETCH = "refetch"

        /** Not a lib code: the shared client was closed under a call. */
        const val DISCONNECTED = "disconnected"

        /** Not a lib code, the supervisor's: something other than a [T3Exception] failed. */
        const val INTERNAL_ERROR = ConnectionSupervisor.INTERNAL_ERROR

        /** `IllegalStateException` messages of a handle closed under a call. */
        private val CLOSED = setOf("client_closed", "subscription_closed")

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
        const val CACHE_RESUME_TIMEOUT_MS = 3_000L

        /** Stream ends after which events may have been lost: the snapshot is read again. */
        val REFETCH_ERRORS = setOf("stream_backpressure", ConnectionSupervisor.STREAM_CLOSED)

        /** Errors after which only [retry] (or a new connection) tries again. */
        val FATAL = setOf("unauthorized", "invalid_thread_id", "http_404", INTERNAL_ERROR)
    }
}
