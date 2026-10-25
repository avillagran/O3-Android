package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import dev.avillagran.ot3android.core.connection.MemoryCursorStore
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.shell.ShellFixtures
import dev.avillagran.ot3android.core.thread.ThreadFixtures.bounded
import dev.avillagran.ot3android.core.thread.ThreadFixtures.event
import dev.avillagran.ot3android.core.thread.ThreadFixtures.message
import dev.avillagran.ot3android.core.thread.ThreadFixtures.projection
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.FakeT3TransportFactory
import dev.avillagran.ot3android.core.transport.ThreadUpdates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** A [ThreadSession] on a real [ConnectionSupervisor]'s `connection`, over fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadSessionConnectionTest {

    private val baseUrl = "http://t3.test:3773"
    private val factory = FakeT3TransportFactory {
        FakeT3Transport().apply {
            onFetchDescriptor(baseUrl) { JSONObject().put("environmentId", "env-1") }
            onShell { ShellFixtures.body(5, listOf(ShellFixtures.project("p1")), listOf(ShellFixtures.thread("t1"))) }
            onBoundedThread("t1") { bounded(10, projection(messages = listOf(message("m1", "hello", "1", role = "user")))) }
        }
    }
    private val fake: FakeT3Transport get() = factory.created.last()

    private fun TestScope.supervisor() = ConnectionSupervisor(
        environment = Environment(id = "local-1", label = "Workstation", baseUrl = baseUrl, createdAt = 0),
        token = { "token-12345678" },
        cursorStore = MemoryCursorStore(),
        transportFactory = factory,
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        random = Random(7),
        io = StandardTestDispatcher(testScheduler),
        computation = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun aReconnectOfTheSameHandleResumesTheThreadFromItsCursor() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()
        val first = supervisor.connection.value!!
        val session = ThreadSession(
            "t1", supervisor.connection, backgroundScope,
            clock = { testScheduler.currentTime }, random = Random(7),
            dispatcher = StandardTestDispatcher(testScheduler),
        ).also { it.start() }
        runCurrent()
        fake.collectors("t1").first { it > 0 }
        fake.emitThread("t1", ThreadUpdates.event(event(11, "message.updated", message("m2", "Hi", "2"))))
        runCurrent()

        // The shell stream is cancelled: the supervisor connects the same handle again, which
        // ends the thread stream as a cancel too, and publishes a new epoch.
        fake.collectors().first { it > 0 }
        fake.emitShell(ThreadUpdates.closed(null))
        runCurrent()
        advanceTimeBy(ConnectionSupervisor.CANCEL_REOPEN_MS + ThreadSession.CANCEL_REOPEN_MS + 1)
        runCurrent()

        val second = supervisor.connection.value!!
        assertEquals("one handle", 1, factory.created.size)
        assertEquals(first.transport, second.transport)
        assertEquals(first.epoch + 1, second.epoch)
        assertEquals(listOf<Long?>(10, 11), fake.streamOpens.filter { it.threadId == "t1" }.map { it.afterSequence })
        assertEquals("no second snapshot", 1, fake.log.count { it.method == "boundedThread" })
        assertEquals(1, fake.collectors("t1").value)
        assertFalse(session.view.value.stale)
        assertEquals(listOf("m1", "m2"), session.view.value.messages.map { it.id })

        supervisor.stop()
        runCurrent()
        assertNull(supervisor.connection.value)
        assertEquals(ThreadConnection.Waiting, session.view.value.connectionState)
        assertEquals(0, fake.collectors("t1").value)
    }
}
