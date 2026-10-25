package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.ThreadUpdate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/** Stream flows through the real Rust core against a scripted local WebSocket. */
class NativeStreamTest {

    private lateinit var transport: NativeT3Transport

    @Before
    fun setUp() {
        assumeHostJni()
        transport = NativeT3Transport()
    }

    @After
    fun tearDown() {
        if (::transport.isInitialized) runBlocking { transport.shutdown() }
    }

    @Test
    fun threadStreamEndsWithTheClosedUpdate() = runBlocking {
        val server = FakeWebSocketServer { request, ws ->
            assertEquals("orchestration.subscribeThread", request.getString("tag"))
            val payload = request.getJSONObject("payload")
            assertEquals("t-1", payload.getString("threadId"))
            assertEquals(7L, payload.getLong("afterSequence"))
            assertTrue(payload.getBoolean("acceptBoundedSnapshot"))
            assertFalse(payload.getBoolean("acceptCompactTurnItems"))
            ws.chunk(
                item("kind" to "snapshot", "snapshotSequence" to 20, "projection" to item("id" to "t-1")),
                item("kind" to "synchronized"),
            )
            check(ws.awaitTag("Ack"))
            ws.chunk(item("kind" to "event", "sequence" to 21, "event" to item("type" to "turn.started")))
            check(ws.awaitTag("Ack"))
            // Then the socket drops: the stream must end with a closed update, not hang.
        }
        server.use {
            transport.connect(ConnectRoute.Bearer(server.baseUrl, "fixture-token-123"))

            val updates = withTimeout(15_000) {
                transport.threadStream("t-1", afterSequence = 7, completionMarker = true).toList()
            }
            server.join()

            val last = updates.last()
            assertTrue(last.closed)
            assertNotNull("a dropped socket carries an error code", last.error)
            assertEquals(1, updates.count { it.closed })
            assertEquals(21L, last.cursor)
            assertEquals(20L, updates.firstNotNullOf { it.snapshot }.getLong("snapshotSequence"))
            assertEquals(listOf(21L), updates.flatMap { it.events }.map { it.getLong("sequence") })
            assertTrue(updates.any { it.synchronized } && updates.any { it.completionMarker })
        }
    }

    @Test
    fun connectEndsOpenStreamsLikeACancel() = runBlocking {
        val server = FakeWebSocketServer { _, ws ->
            ws.chunk(item("kind" to "snapshot", "snapshotSequence" to 4, "projection" to item("id" to "t-1")))
            check(ws.awaitTag("Ack"))
            ws.awaitTag("Interrupt")
        }
        server.use {
            transport.connect(ConnectRoute.Bearer(server.baseUrl, "fixture-token-123"))
            val received = Channel<ThreadUpdate>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                transport.threadStream("t-1").collect { received.send(it) }
                received.close()
            }
            withTimeout(10_000) { while (!received.receive().idle) Unit }

            transport.connect(ConnectRoute.Bearer(server.baseUrl, "fixture-token-456"))

            val rest = withTimeout(5_000) { collector.join(); received.toList() }
            val last = rest.last()
            assertTrue(last.closed)
            assertNull("a switch is a cancel, not a failure", last.error)
            assertEquals(4L, last.cursor)
            server.join()
        }
    }

    @Test
    fun closeReturnsAtOnceAndEndsOpenStreamsLikeACancel() = runBlocking {
        val server = FakeWebSocketServer { _, ws ->
            ws.chunk(item("kind" to "snapshot", "snapshotSequence" to 2, "projection" to item("id" to "shell")))
            check(ws.awaitTag("Ack"))
            ws.awaitTag("Interrupt")
        }
        server.use {
            transport.connect(ConnectRoute.Bearer(server.baseUrl, "fixture-token-123"))
            val received = Channel<ThreadUpdate>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                transport.shellStream().collect { received.send(it) }
                received.close()
            }
            withTimeout(10_000) { while (!received.receive().idle) Unit }
            delay(100) // the next poll is now blocked in native code

            val closeMs = measureTimeMillis { transport.close() }
            withTimeout(5_000) { transport.shutdown() }

            assertTrue("close took $closeMs ms", closeMs < 50)
            val last = withTimeout(5_000) { collector.join(); received.toList() }.last()
            assertTrue(last.closed)
            assertNull(last.error)
            server.join()
        }
    }

    @Test
    fun aServerSideFailureEndsTheStreamWithItsCode() = runBlocking {
        val server = FakeWebSocketServer { _, ws ->
            ws.chunk(item("kind" to "snapshot", "snapshot" to item("snapshotSequence" to 9, "threads" to JSONArray())))
            check(ws.awaitTag("Ack"))
            ws.exit()
        }
        server.use {
            transport.connect(ConnectRoute.Bearer(server.baseUrl, "fixture-token-123"))

            val updates = withTimeout(10_000) { transport.shellStream().toList() }
            server.join()

            val last = updates.last()
            assertTrue(last.closed)
            assertEquals("stream_closed", last.error)
            assertEquals(9L, last.cursor)
            assertEquals(1, updates.count { it.closed })
        }
    }

    @Test
    fun cancellingTheCollectorClosesTheStreamAtOnce() = runBlocking {
        val interrupted = CountDownLatch(1)
        val server = FakeWebSocketServer { _, ws ->
            ws.chunk(item("kind" to "snapshot", "snapshotSequence" to 1, "projection" to item("id" to "shell")))
            if (ws.awaitTag("Ack") && ws.awaitTag("Interrupt")) interrupted.countDown()
        }
        server.use {
            transport.connect(ConnectRoute.Bearer(server.baseUrl, "fixture-token-123"))
            val received = Channel<ThreadUpdate>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                transport.shellStream().collect { received.send(it) }
            }
            withTimeout(10_000) { while (!received.receive().idle) Unit }
            delay(100) // the next poll is now blocked in native code, for up to 1 s

            val cancelMs = measureTimeMillis { collector.cancelAndJoin() }

            assertTrue("cancel took $cancelMs ms", cancelMs < 500)
            assertTrue("server saw no Interrupt", interrupted.await(3, TimeUnit.SECONDS))
            server.join()
        }
    }
}
