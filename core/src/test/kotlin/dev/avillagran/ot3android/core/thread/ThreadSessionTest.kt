package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.thread.ThreadFixtures.bounded
import dev.avillagran.ot3android.core.thread.ThreadFixtures.event
import dev.avillagran.ot3android.core.thread.ThreadFixtures.message
import dev.avillagran.ot3android.core.thread.ThreadFixtures.projection
import dev.avillagran.ot3android.core.thread.ThreadFixtures.run
import dev.avillagran.ot3android.core.thread.ThreadFixtures.snapshotItem
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.ThreadUpdates
import dev.avillagran.t3.ThreadUpdate
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadSessionTest {

    private val fake = FakeT3Transport()
    private val connection = MutableStateFlow<Connection?>(null)
    private val snapshots = AtomicInteger()
    private var snapshotSequence = 10L
    private var snapshotMessages = listOf(message("m1", "hello", "2026-10-25T01:00:00.000Z", role = "user"))

    init {
        fake.onBoundedThread("t1") {
            snapshots.incrementAndGet()
            bounded(snapshotSequence, projection(messages = snapshotMessages))
        }
    }

    private suspend fun TestScope.session(): ThreadSession {
        fake.connect(ConnectRoute.Bearer("http://t3.test", "token-12345678"))
        connection.value = Connection(fake, 1)
        return ThreadSession(
            threadId = "t1",
            connection = connection,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            random = Random(7),
            dispatcher = StandardTestDispatcher(testScheduler),
        ).also { it.start() }
    }

    /** Emits once the session's stream is collected (the fake drops emits without one). */
    private suspend fun TestScope.emit(update: ThreadUpdate) {
        fake.collectors("t1").first { it > 0 }
        fake.emitThread("t1", update)
        runCurrent()
    }

    private fun opens() = fake.streamOpens.map { it.afterSequence }

    private fun TestScope.skipBackoff(session: ThreadSession) {
        val retrying = session.view.value.connectionState as ThreadConnection.Retrying
        advanceTimeBy(retrying.nextRetryAt - testScheduler.currentTime + 1)
        runCurrent()
    }

    @Test
    fun opensWithTheBoundedSnapshotThenFollowsTheStream() = runTest {
        val session = session()
        runCurrent()

        val view = session.view.value
        assertTrue(view.loaded)
        assertEquals("Thread t1", view.title)
        assertEquals(listOf("m1"), view.messages.map { it.id })
        assertEquals(1, snapshots.get())
        assertEquals(FakeT3Transport.StreamOpen("t1", 10, false, boundedSnapshot = true, compactTurnItems = false), fake.streamOpens.single())

        emit(ThreadUpdates.batch(event(11, "message.updated", message("m2", "Hi", "2026-10-25T01:00:01.000Z", streaming = true)), repaint = false))
        assertEquals(ThreadConnection.Live, session.view.value.connectionState)
        assertEquals("not painted mid-chunk", 1, session.view.value.messages.size)

        emit(ThreadUpdates.event(event(12, "run.updated", run("r1", "running"))))
        val live = session.view.value
        assertEquals(listOf("m1", "m2"), live.messages.map { it.id })
        assertTrue(live.messages.last().streaming)
        assertTrue(live.working)
        assertEquals("r1", live.activeRunId)
        assertEquals(12L, live.sequence)
        assertTrue(live.modelSelection!!.contains("gpt-5"))
    }

    @Test
    fun aTransientCloseResubscribesFromTheCursorWithoutAnotherSnapshot() = runTest {
        val session = session()
        runCurrent()
        emit(ThreadUpdates.event(event(11, "message.updated", message("m2", "Hi", "2"))))

        emit(ThreadUpdates.closed("stream_timeout"))
        val retrying = session.view.value.connectionState as ThreadConnection.Retrying
        assertEquals("stream_timeout", retrying.error)
        assertTrue("first delay", retrying.nextRetryAt - testScheduler.currentTime in 500L..1_000L)
        assertEquals("the view stays", 2, session.view.value.messages.size)
        assertFalse(session.view.value.stale)

        skipBackoff(session)
        assertEquals(listOf<Long?>(10, 11), opens())
        assertEquals(1, snapshots.get())
    }

    @Test
    fun aClosedStreamStartingWithASnapshotReplaces() = runTest {
        val session = session()
        runCurrent()
        emit(ThreadUpdates.closed("disconnected"))
        skipBackoff(session)

        val fresh = projection(messages = listOf(message("n1", "fresh", "3")))
        emit(ThreadUpdates.batch(snapshotItem(50, fresh), start = "snapshot"))
        assertEquals(listOf("n1"), session.view.value.messages.map { it.id })
        assertEquals(50L, session.view.value.sequence)
    }

    @Test
    fun backpressureRefetchesTheSnapshotAtMostOncePerInterval() = runTest {
        val session = session()
        runCurrent()

        emit(ThreadUpdates.closed("stream_backpressure"))
        assertTrue("stale until refetched", session.view.value.stale)
        assertEquals(1, session.view.value.messages.size)

        snapshotSequence = 30
        snapshotMessages = snapshotMessages + message("m3", "later", "4")
        skipBackoff(session)
        assertEquals("the backoff ended inside the interval: wait for its end", 1, snapshots.get())
        advanceTimeBy(ThreadSession.REFETCH_INTERVAL_MS - testScheduler.currentTime)
        runCurrent()

        assertEquals(2, snapshots.get())
        assertEquals(listOf<Long?>(10, 30), opens())
        assertFalse(session.view.value.stale)
        assertEquals(listOf("m1", "m3"), session.view.value.messages.map { it.id })

        // Another one right away waits for the trailing edge of the interval again.
        val refetchedAt = testScheduler.currentTime
        emit(ThreadUpdates.closed("stream_closed"))
        skipBackoff(session)
        assertEquals(2, snapshots.get())
        advanceTimeBy(refetchedAt + ThreadSession.REFETCH_INTERVAL_MS - testScheduler.currentTime)
        runCurrent()
        assertEquals(3, snapshots.get())
    }

    @Test
    fun fiftyTurnItemUpdatesNeverRefetch() = runTest {
        val session = session()
        runCurrent()

        repeat(50) { i ->
            val item = JSONObject().put("id", "i$i").put("type", "command_execution").put("status", "running")
            emit(ThreadUpdates.event(event(11L + i, "turn-item.updated", item)))
        }
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, fake.log.count { it.method == "boundedThread" })
        assertFalse(session.view.value.stale)
        assertEquals(60L, session.view.value.sequence)
        assertEquals("one stream, never restarted", listOf<Long?>(10), opens())
        assertEquals(ThreadConnection.Live, session.view.value.connectionState)
    }

    @Test
    fun threadVisitedOnlyMovesTheCursor() = runTest {
        val session = session()
        runCurrent()
        // As observed live: an idle poll, then the event with start = resume.
        emit(ThreadUpdates.idle())
        emit(ThreadUpdates.batch(event(11, "thread.visited", JSONObject().put("threadId", "t1")), start = "resume"))
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(11L, session.view.value.sequence)
        assertEquals(1, fake.log.count { it.method == "boundedThread" })
        assertFalse(session.view.value.stale)
        assertEquals(listOf("m1"), session.view.value.messages.map { it.id })
    }

    @Test
    fun aMalformedStreamSnapshotRefetches() = runTest {
        val session = session()
        runCurrent()
        snapshotSequence = 40
        emit(ThreadUpdates.batch(JSONObject().put("kind", "snapshot").put("snapshotSequence", 39)))
        assertTrue(session.view.value.stale)
        assertEquals("rate-limited", 1, snapshots.get())

        advanceTimeBy(ThreadSession.REFETCH_INTERVAL_MS)
        runCurrent()
        assertEquals(2, snapshots.get())
        assertEquals(listOf<Long?>(10, 40), opens())
        assertFalse(session.view.value.stale)
    }

    @Test
    fun aMissingThreadFailsUntilRetried() = runTest {
        fake.onBoundedThread("t1") { throw T3Exception("http_404") }
        val session = session()
        runCurrent()
        assertEquals(ThreadConnection.Failed("http_404"), session.view.value.connectionState)

        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(fake.streamOpens.isEmpty())

        fake.onBoundedThread("t1") { bounded(5, projection()) }
        session.retry()
        runCurrent()
        assertTrue(session.view.value.loaded)
        assertEquals(listOf<Long?>(5), opens())
    }

    @Test
    fun losingTheConnectionPausesAndResumesFromTheCursor() = runTest {
        val session = session()
        runCurrent()
        emit(ThreadUpdates.event(event(11, "message.updated", message("m2", "Hi", "2"))))

        connection.value = null
        runCurrent()
        assertEquals(ThreadConnection.Waiting, session.view.value.connectionState)
        assertEquals("the stream was closed", 0, fake.collectors("t1").value)

        connection.value = Connection(fake, 1)
        runCurrent()
        assertEquals(listOf<Long?>(10, 11), opens())
        assertEquals(1, snapshots.get())
    }

    @Test
    fun aNewEpochOfTheSameTransportReopensTheStreamFromTheCursor() = runTest {
        val session = session()
        runCurrent()
        emit(ThreadUpdates.event(event(11, "message.updated", message("m2", "Hi", "2"))))

        // The supervisor connected the same handle again: a new epoch restarts the stream.
        connection.value = Connection(fake, 2)
        runCurrent()

        assertEquals(listOf<Long?>(10, 11), opens())
        assertEquals("no second snapshot", 1, snapshots.get())
        assertEquals("the old stream was closed", 1, fake.collectors("t1").value)
        assertFalse(session.view.value.stale)
    }

    @Test
    fun aCancelReopensFromTheCursorWithoutASnapshotOrStale() = runTest {
        val session = session()
        runCurrent()
        emit(ThreadUpdates.event(event(11, "message.updated", message("m2", "Hi", "2"))))

        emit(ThreadUpdates.closed(null))
        assertFalse(session.view.value.stale)
        assertFalse(session.view.value.connectionState is ThreadConnection.Retrying)
        advanceTimeBy(ThreadSession.CANCEL_REOPEN_MS + 1)
        runCurrent()

        assertEquals(listOf<Long?>(10, 11), opens())
        assertEquals(1, snapshots.get())
        assertEquals(2, session.view.value.messages.size)
    }

    @Test
    fun aHandleThatKeepsCancellingBacksOffAfterThreeQuickReopens() = runTest {
        val session = session()
        runCurrent()
        repeat(ThreadSession.MAX_QUICK_CANCELS) {
            emit(ThreadUpdates.closed(null))
            advanceTimeBy(ThreadSession.CANCEL_REOPEN_MS + 1)
            runCurrent()
        }
        assertEquals(ThreadSession.MAX_QUICK_CANCELS + 1, fake.streamOpens.size)

        emit(ThreadUpdates.closed(null))
        val retrying = session.view.value.connectionState as ThreadConnection.Retrying
        assertEquals(ThreadSession.DISCONNECTED, retrying.error)
        advanceTimeBy(ThreadSession.CANCEL_REOPEN_MS + 1)
        runCurrent()
        assertEquals("waits for the backoff", ThreadSession.MAX_QUICK_CANCELS + 1, fake.streamOpens.size)
        skipBackoff(session)
        assertEquals(ThreadSession.MAX_QUICK_CANCELS + 2, fake.streamOpens.size)
        assertEquals(1, snapshots.get())
        assertFalse(session.view.value.stale)
    }

    @Test
    fun aCancelFollowedByNoConnectionStops() = runTest {
        val session = session()
        runCurrent()
        emit(ThreadUpdates.closed(null))
        connection.value = null
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(ThreadConnection.Waiting, session.view.value.connectionState)
        assertEquals("not reopened", 1, fake.streamOpens.size)
        assertEquals(0, fake.collectors("t1").value)
    }

    @Test
    fun aSubscriptionLimitIsShownAndBacksOff() = runTest {
        fake.failNextStream(T3Exception("subscription_limit"))
        val session = session()
        runCurrent()

        val retrying = session.view.value.connectionState as ThreadConnection.Retrying
        assertEquals("subscription_limit", retrying.error)
        assertTrue(session.view.value.loaded)
        assertFalse(session.view.value.stale)
        assertTrue(fake.streamOpens.isEmpty())

        skipBackoff(session)
        assertEquals(listOf<Long?>(10), opens())
        assertEquals(1, snapshots.get())
    }
}
