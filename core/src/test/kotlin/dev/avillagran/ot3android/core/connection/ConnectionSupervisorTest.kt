package dev.avillagran.ot3android.core.connection

import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.shell.ShellFixtures.body
import dev.avillagran.ot3android.core.shell.ShellFixtures.identityRefresh
import dev.avillagran.ot3android.core.shell.ShellFixtures.project
import dev.avillagran.ot3android.core.shell.ShellFixtures.snapshot
import dev.avillagran.ot3android.core.shell.ShellFixtures.thread
import dev.avillagran.ot3android.core.shell.ShellFixtures.threadUpdated
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.t3.T3Batch
import dev.avillagran.t3.T3Exception
import dev.avillagran.t3.ThreadUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSupervisorTest {

    private val baseUrl = "http://t3.test:3773"
    private val fake = FakeT3Transport()
    private val cursors = MemoryCursorStore()
    private val shellRequests = AtomicInteger()
    private val connected = mutableListOf<ServerDescriptor>()
    private var shellSequence = 5L
    private var token: String? = "token-12345678"

    private val descriptor = JSONObject().put("environmentId", "env-1").put("label", "Workstation")
        .put("serverVersion", "0.1.3").put("orchestrationProtocolVersion", 2)
        .put("platform", JSONObject().put("os", "linux"))

    init {
        fake.onDescriptor(baseUrl) { descriptor }
        fake.onRequest(SHELL_PATH) {
            shellRequests.incrementAndGet()
            body(shellSequence, listOf(project("p1")), listOf(thread("t1"), thread("t2")))
        }
    }

    private fun TestScope.supervisor() = ConnectionSupervisor(
        environment = Environment(id = "local-1", label = "Workstation", baseUrl = baseUrl, createdAt = 0),
        token = { token },
        cursorStore = cursors,
        transportFactory = { fake },
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        random = Random(7),
        onConnected = { connected += it },
        io = StandardTestDispatcher(testScheduler),
    )

    private fun update(vararg items: JSONObject, repaint: Boolean = true, closed: Boolean = false, error: String? = null) =
        ThreadUpdate(
            snapshot = null, events = emptyList(), repaint = repaint, synchronized = false, cursor = null,
            start = null, idle = repaint, completionMarker = false, closed = closed, error = error, errorMessage = null,
            batch = T3Batch(items = JSONArray(items.toList()), closed = closed, error = error),
        )

    @Test
    fun connectsThenFollowsTheShellStream() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        val state = supervisor.state.value as ConnectionState.Connected
        assertEquals("Workstation", state.descriptor.label)
        assertEquals("0.1.3", state.descriptor.serverVersion)
        assertEquals(listOf(ConnectRoute.Bearer(baseUrl, "token-12345678")), fake.routes)
        assertEquals(listOf("env-1"), connected.map { it.environmentId })
        assertEquals(5L, supervisor.shell.value?.sequence)
        assertEquals(FakeT3Transport.StreamOpen(null, 5, false), fake.streamOpens.single())

        fake.emitShell(update(threadUpdated(6, thread("t3")), repaint = false))
        runCurrent()
        assertEquals("not painted mid-chunk", 2, supervisor.shell.value?.threads?.size)

        fake.emitShell(update())
        runCurrent()
        assertEquals(listOf("t1", "t2", "t3"), supervisor.shell.value?.threads?.map { it.id })
        assertEquals(6L, cursors.cursors.value["local-1"])
    }

    @Test
    fun aMetadataSnapshotInTheSameBatchKeepsThreadsAndCursor() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        // As seen live: the authoritative snapshot and the identity refresh in one batch.
        fake.emitShell(
            update(
                snapshot(10, listOf(project("p1")), listOf(thread("a"), thread("b"), thread("c"))),
                identityRefresh(40, listOf("/w/p1"), listOf(project("p1", identity = "repo"))),
            ),
        )
        runCurrent()

        val shell = supervisor.shell.value!!
        assertEquals(10L, shell.sequence)
        assertEquals(listOf("a", "b", "c"), shell.threads.map { it.id })
        assertEquals("""{"canonicalKey":"repo"}""", shell.projects.single().repositoryIdentity)
        assertEquals(10L, cursors.cursors.value["local-1"])
    }

    @Test
    fun unauthorizedStopsWithoutRetrying() = runTest {
        fake.onRequest(SHELL_PATH) { throw T3Exception("unauthorized") }
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        assertEquals(ConnectionState.Unauthorized, supervisor.state.value)
        assertFalse(supervisor.isRunning)
        assertTrue(fake.streamOpens.isEmpty())
        assertTrue(connected.isEmpty())

        advanceTimeBy(600_000)
        runCurrent()
        assertEquals("no automatic retry", 1, fake.routes.size)

        fake.onRequest(SHELL_PATH) { body(5, emptyList(), emptyList()) }
        supervisor.retry()
        runCurrent()
        assertTrue(supervisor.state.value is ConnectionState.Connected)
    }

    @Test
    fun unreachableSchedulesAJitteredRetry() = runTest {
        var failures = 2
        fake.onDescriptor(baseUrl) {
            if (failures-- > 0) throw T3Exception("unreachable") else descriptor
        }
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        val first = supervisor.state.value as ConnectionState.Unreachable
        assertEquals("unreachable", first.error)
        val firstDelay = first.nextRetryAt!! - testScheduler.currentTime
        assertTrue("first delay $firstDelay", firstDelay in 500L..1_000L)

        advanceTimeBy(firstDelay + 1)
        runCurrent()
        val second = supervisor.state.value as ConnectionState.Unreachable
        val secondDelay = second.nextRetryAt!! - testScheduler.currentTime
        assertTrue("second delay $secondDelay", secondDelay in 1_000L..2_000L)

        advanceTimeBy(secondDelay + 1)
        runCurrent()
        assertTrue(supervisor.state.value is ConnectionState.Connected)
    }

    @Test
    fun wakeSkipsAPendingBackoff() = runTest {
        var failures = 1
        fake.onDescriptor(baseUrl) { if (failures-- > 0) throw T3Exception("unreachable") else descriptor }
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()
        assertTrue(supervisor.state.value is ConnectionState.Unreachable)

        supervisor.wake()
        runCurrent()

        assertTrue(supervisor.state.value is ConnectionState.Connected)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun aTransientCloseResubscribesFromTheCursorWithoutAnotherShellGet() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()
        fake.emitShell(update(threadUpdated(6, thread("t3"))))
        runCurrent()

        fake.emitShell(update(closed = true, error = "stream_timeout"))
        runCurrent()
        val waiting = supervisor.state.value as ConnectionState.Unreachable
        assertEquals("stream_timeout", waiting.error)
        assertEquals("the shell stays visible", 3, supervisor.shell.value?.threads?.size)

        advanceTimeBy(waiting.nextRetryAt!! - testScheduler.currentTime + 1)
        runCurrent()

        assertTrue(supervisor.state.value is ConnectionState.Connected)
        assertEquals(listOf<Long?>(5, 6), fake.streamOpens.map { it.afterSequence })
        assertEquals(1, shellRequests.get())
    }

    @Test
    fun aNonTransientCloseDropsTheShellAndStartsFresh() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()
        fake.emitShell(update(threadUpdated(6, thread("t3"))))
        runCurrent()

        fake.emitShell(update(closed = true, error = "stream_closed"))
        runCurrent()
        assertNull(supervisor.shell.value)
        assertNull(cursors.cursors.value["local-1"])

        shellSequence = 50
        val waiting = supervisor.state.value as ConnectionState.Unreachable
        advanceTimeBy(waiting.nextRetryAt!! - testScheduler.currentTime + 1)
        runCurrent()

        assertEquals(2, shellRequests.get())
        assertEquals(listOf("t1", "t2"), supervisor.shell.value?.threads?.map { it.id })
        assertEquals(listOf<Long?>(5, 50), fake.streamOpens.map { it.afterSequence })
    }

    @Test
    fun fatalErrorsAndAMissingTokenDoNotRetry() = runTest {
        token = null
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        assertEquals(ConnectionState.Unreachable(ConnectionSupervisor.MISSING_TOKEN, null), supervisor.state.value)
        assertFalse(supervisor.isRunning)
    }

    @Test
    fun stopClosesTheTransportAndKeepsTheShellForResume() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()
        fake.emitShell(update(threadUpdated(6, thread("t3"))))
        runCurrent()

        supervisor.stop()
        runCurrent()
        assertEquals(ConnectionState.Disconnected, supervisor.state.value)
        assertTrue(fake.closed)
        assertEquals(3, supervisor.shell.value?.threads?.size)

        supervisor.start()
        runCurrent()
        assertEquals(listOf<Long?>(5, 6), fake.streamOpens.map { it.afterSequence })
        assertEquals(1, shellRequests.get())
    }

    @Test
    fun refreshReplacesTheShell() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        shellSequence = 9
        supervisor.refresh()

        assertEquals(9L, supervisor.shell.value?.sequence)
        assertEquals(2, shellRequests.get())
    }
}
