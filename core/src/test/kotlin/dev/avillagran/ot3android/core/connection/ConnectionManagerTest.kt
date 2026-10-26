package dev.avillagran.ot3android.core.connection

import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.InMemoryEnvironmentDao
import dev.avillagran.ot3android.core.data.InMemoryTokenStore
import dev.avillagran.ot3android.core.shell.ShellFixtures.body
import dev.avillagran.ot3android.core.shell.ShellFixtures.thread
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.FakeT3TransportFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {

    @Test
    fun followLeasesShareOneTransportAndOnlyLastBackgroundReleaseStopsIt() = runTest {
        val manager = manager()
        val screen = manager.supervisor("id-1")!!
        manager.startIfCurrent(screen)
        val first = manager.acquireFollowLease("id-1")!!
        val second = manager.acquireFollowLease("id-1")!!
        runCurrent()
        assertEquals(1, factory.created.size)
        manager.setForeground(false)
        runCurrent()
        assertTrue(screen.isRunning)
        first.close(); first.close()
        runCurrent()
        assertTrue(screen.isRunning)
        second.close()
        runCurrent()
        assertFalse(screen.isRunning)
        manager.setForeground(true)
        runCurrent()
        assertTrue(screen.isRunning)
        assertEquals(2, factory.created.size)
    }

    @Test
    fun followLeaseSurvivesRouteReplacementWithoutRevivingOrphan() = runTest {
        val manager = manager()
        val lease = manager.acquireFollowLease("id-1")!!
        runCurrent()
        val previous = manager.supervisor("id-1")!!
        manager.setForeground(false)
        manager.forget("id-1")
        val replacement = manager.supervisor("id-1")!!
        manager.startIfCurrent(replacement)
        runCurrent()
        assertTrue(replacement.isRunning)
        assertFalse(previous.isRunning)
        assertEquals(2, factory.created.size)
        lease.close()
        runCurrent()
        assertFalse(replacement.isRunning)
    }

    @Test
    fun deferredConsumersResumeOnForegroundButCannotReviveForgottenSupervisors() = runTest {
        val manager = manager()
        manager.setForeground(false)
        val current = manager.supervisor("id-1")!!
        manager.startIfCurrent(current)
        runCurrent()
        assertFalse(current.isRunning)
        assertEquals(0, factory.created.size)
        manager.setForeground(true)
        runCurrent()
        assertTrue(current.state.value is ConnectionState.Connected)
        manager.forget("id-1")
        manager.startIfCurrent(current)
        runCurrent()
        assertFalse(current.isRunning)
        assertEquals("forgotten transport is not reopened", 1, factory.created.size)
    }

    @Test
    fun connectionConsumersFollowReplacementWithoutDuplicatingTheTransport() = runTest {
        val manager = manager()
        val seen = mutableListOf<Connection?>()
        backgroundScope.launch { manager.connectionFlow("id-1").collect { seen += it } }
        backgroundScope.launch { manager.connectionFlow("id-1").collect { } }
        runCurrent()
        val original = seen.last()!!
        assertEquals(1, factory.created.size)
        manager.forget("id-1")
        runCurrent()
        assertNull(seen.last())
        manager.supervisor("id-1")
        runCurrent()
        assertNotSame(original.transport, seen.last()!!.transport)
        assertTrue(factory.created.first().closed)
        assertEquals("one new transport, shared by both consumers", 2, factory.created.size)
    }

    private val cursors = MemoryCursorStore()
    private val dao = InMemoryEnvironmentDao()
    private var ids = 0
    private val tokens = InMemoryTokenStore()
    private val repository = EnvironmentRepository(dao, { tokens }, cursors, newId = { "id-${++ids}" })

    /** Every transport answers for both test servers. */
    private val factory = FakeT3TransportFactory {
        FakeT3Transport().apply {
            for (url in listOf("http://one:3773", "http://two:3773")) {
                onFetchDescriptor(url) { JSONObject().put("environmentId", url.substringAfter("//").substringBefore(':')) }
            }
            onShell { body(5, emptyList(), listOf(thread("t1"))) }
        }
    }

    private suspend fun TestScope.manager(): ConnectionManager {
        repository.save(null, "One", "http://one:3773", null, "t1")
        repository.save(null, "Two", "http://two:3773", null, "t2")
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ConnectionManager(
            repository, cursors, factory, backgroundScope,
            clock = { testScheduler.currentTime }, random = Random(3), io = dispatcher, computation = dispatcher,
        )
    }

    @Test
    fun supervisorsAreCreatedOnceAndOnlyForSavedEnvironments() = runTest {
        val manager = manager()

        val one = manager.supervisor("id-1")!!
        assertSame(one, manager.supervisor("id-1"))
        assertNull(manager.supervisor("nope"))
        assertFalse("created, not started", one.isRunning)
        assertEquals(setOf("id-1"), manager.supervisors.value.keys)
    }

    @Test
    fun theBackgroundStopsRunningSupervisorsAndTheForegroundResumesOnlyThose() = runTest {
        val manager = manager()
        val one = manager.supervisor("id-1")!!
        val two = manager.supervisor("id-2")!!
        one.start()
        runCurrent()
        assertTrue(one.state.value is ConnectionState.Connected)

        manager.setForeground(false)
        runCurrent()
        assertEquals(ConnectionState.Disconnected, one.state.value)
        assertTrue(factory.created.single().closed)
        assertEquals("the shell stays", 1, one.shell.value?.threads?.size)

        manager.setForeground(true)
        runCurrent()
        assertTrue(one.state.value is ConnectionState.Connected)
        assertFalse("never started, so not resumed", two.isRunning)
        assertEquals(2, factory.created.size)
    }

    @Test
    fun forgetClosesTheTransportAndCancelsTheRun() = runTest {
        val manager = manager()
        val one = manager.supervisor("id-1")!!
        one.start()
        runCurrent()

        manager.forget("id-1")

        assertFalse(one.isRunning)
        assertTrue(factory.created.single().closed)
        assertEquals(ConnectionState.Disconnected, one.state.value)
        assertTrue(manager.supervisors.value.isEmpty())
        assertNotSame("a later lookup builds a new one", one, manager.supervisor("id-1"))
    }

    @Test
    fun wakeAllProbesTheOpenStreams() = runTest {
        val manager = manager()
        manager.supervisor("id-1")!!.start()
        runCurrent()
        factory.created.single().collectors().first { it > 0 }

        manager.wakeAll()
        runCurrent()

        assertEquals(1, factory.created.single().wakes)
    }

    @Test
    fun statesFollowEverySupervisor() = runTest {
        val manager = manager()
        manager.supervisor("id-1")!!.start()
        manager.supervisor("id-2")
        runCurrent()

        val states = manager.states.first { it.size == 2 }
        assertTrue(states["id-1"] is ConnectionState.Connected)
        assertEquals(ConnectionState.Disconnected, states["id-2"])
    }
}
