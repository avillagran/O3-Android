package dev.avillagran.ot3android.core.connection

import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.shell.ShellFixtures.body
import dev.avillagran.ot3android.core.shell.ShellFixtures.project
import dev.avillagran.ot3android.core.shell.ShellFixtures.thread
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.FakeT3TransportFactory
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** A manual LAN row off the LAN, with the catalog's route to the same server. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRouteFallbackTest {
    private val lan = "http://192.168.1.177:3773"
    private val gateway = "https://gateway.example:39670/"
    private val token = "token-12345678"
    private val cursors = MemoryCursorStore()
    private val shellRequests = AtomicInteger()
    private val descriptor = JSONObject().put("environmentId", "env-1")
    private val fallbacks = MutableStateFlow(mapOf("local-1" to gateway))

    private var lanAnswer: suspend () -> JSONObject = { throw T3Exception("unreachable") }
    private var gatewayAnswer: suspend () -> JSONObject = { descriptor }
    private var shellAnswer: suspend () -> JSONObject = {
        shellRequests.incrementAndGet()
        body(5, listOf(project("p1")), listOf(thread("t1")))
    }

    private val factory = FakeT3TransportFactory {
        FakeT3Transport().apply {
            onFetchDescriptor(lan) { lanAnswer() }
            onFetchDescriptor(gateway) { gatewayAnswer() }
            onShell { shellAnswer() }
        }
    }

    /** The run's transport; later ones are credential-free probes. */
    private val run: FakeT3Transport get() = factory.created.first()

    private fun descriptorReads(url: String) =
        factory.created.flatMap { it.log }.count { it.method == "fetchDescriptor" && it.args.first() == url }

    private fun TestScope.supervisor(environmentId: String? = "env-1") = ConnectionSupervisor(
        environment = Environment(id = "local-1", label = "NODE", baseUrl = lan, environmentId = environmentId, createdAt = 0),
        token = { token },
        cursorStore = cursors,
        transportFactory = factory,
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        random = Random(7),
        io = StandardTestDispatcher(testScheduler),
        computation = StandardTestDispatcher(testScheduler),
        routeFallbacks = fallbacks,
    )

    private fun connectedVia(state: ConnectionState, fallback: Boolean) {
        assertTrue("was $state", state is ConnectionState.Connected)
        assertEquals(fallback, (state as ConnectionState.Connected).viaFallback)
    }

    @Test
    fun usesTheCatalogRouteWhileItsOwnUrlIsOutOfReach() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        connectedVia(supervisor.state.value, fallback = true)
        assertEquals(listOf(ConnectRoute.Bearer(gateway, token)), run.routes.toList())
        assertEquals(1, shellRequests.get())
    }

    @Test
    fun goesBackWhenItsOwnUrlAnswersResumingFromTheHeldCursor() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        lanAnswer = { descriptor }
        advanceTimeBy(ConnectionSupervisor.PROBE_FIRST_MS + 1)
        runCurrent()

        connectedVia(supervisor.state.value, fallback = false)
        assertEquals(ConnectRoute.Bearer(lan, token), run.routes.last())
        assertEquals("the same server: no new shell", 1, shellRequests.get())
        assertEquals(5L, run.streamOpens.last().afterSequence)
        assertTrue("a probe carries no credential", factory.created.drop(1).all { it.routes.isEmpty() })
    }

    @Test
    fun aNetworkChangeProbesItsOwnUrlAtOnce() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        lanAnswer = { descriptor }
        supervisor.wake()
        runCurrent()

        connectedVia(supervisor.state.value, fallback = false)
    }

    @Test
    fun probesStayOnBackoffWhileItsOwnUrlStaysAway() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()
        val before = descriptorReads(lan)

        advanceTimeBy(10 * 60_000L)
        runCurrent()

        val probes = descriptorReads(lan) - before
        assertTrue("$probes probes in ten minutes", probes in 3..30)
        connectedVia(supervisor.state.value, fallback = true)
    }

    @Test
    fun aDifferentServerAtTheFallbackNeverSeesTheToken() = runTest {
        gatewayAnswer = { JSONObject().put("environmentId", "someone-else") }
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        val state = supervisor.state.value
        assertTrue("was $state", state is ConnectionState.Unreachable)
        assertEquals("unreachable", (state as ConnectionState.Unreachable).error)
        assertNotNull("still retrying its own URL", state.nextRetryAt)
        assertTrue(run.routes.isEmpty())

        advanceTimeBy(10 * 60_000L)
        assertEquals("refused once, not asked again", 1, descriptorReads(gateway))
        assertTrue(descriptorReads(lan) > 2)
    }

    @Test
    fun aTokenRefusedAtTheFallbackIsNotTheRowsFailureButIsReported() = runTest {
        shellAnswer = { throw T3Exception("unauthorized") }
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        assertEquals("unreachable", (supervisor.state.value as ConnectionState.Unreachable).error)
        assertEquals(FallbackRefusal(gateway, "unauthorized"), supervisor.fallback.value.refused)
        advanceTimeBy(10 * 60_000L)
        assertEquals("the token is not offered again", 1, run.routes.size)
        assertEquals("through every retry", FallbackRefusal(gateway, "unauthorized"), supervisor.fallback.value.refused)
    }

    @Test
    fun aRouteTheUserApprovesAgainAfterARefusalGetsANewTry() = runTest {
        shellAnswer = { throw T3Exception("unauthorized") }
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        fallbacks.value = emptyMap()
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()
        assertNull(supervisor.fallback.value.refused)

        shellAnswer = { shellRequests.incrementAndGet(); body(5, listOf(project("p1")), listOf(thread("t1"))) }
        fallbacks.value = mapOf("local-1" to gateway)
        runCurrent()
        connectedVia(supervisor.state.value, fallback = true)
    }

    @Test
    fun theRowReportsItsOwnRouteAndTheFallbackInUse() = runTest {
        fallbacks.value = emptyMap()
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()
        assertEquals(FallbackStatus(ownRouteDown = true), supervisor.fallback.value)

        fallbacks.value = mapOf("local-1" to gateway)
        runCurrent()
        assertEquals(FallbackStatus(ownRouteDown = true, inUse = gateway), supervisor.fallback.value)

        run.emitError(T3Exception("stream_timeout"))
        runCurrent()
        assertEquals("still its route while it reconnects", gateway, supervisor.fallback.value.inUse)

        lanAnswer = { descriptor }
        advanceTimeBy(ConnectionSupervisor.PROBE_MAX_MS + 2_000)
        runCurrent()
        assertEquals(FallbackStatus(), supervisor.fallback.value)
    }

    @Test
    fun aFallbackNotApprovedIsNeverContacted() = runTest {
        fallbacks.value = emptyMap()
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()
        advanceTimeBy(10 * 60_000L)

        assertEquals(0, descriptorReads(gateway))
        assertTrue(run.routes.isEmpty())
    }

    @Test
    fun whenBothRoutesFailOneCycleCostsOneJitteredBackoffStep() = runTest {
        gatewayAnswer = { throw T3Exception("unreachable") }
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        assertEquals(1 to 1, descriptorReads(lan) to descriptorReads(gateway))
        val first = (supervisor.state.value as ConnectionState.Unreachable).nextRetryAt!!
        assertTrue(first in 500..1_000)

        advanceTimeBy(first + 1)
        runCurrent()
        assertEquals(2 to 2, descriptorReads(lan) to descriptorReads(gateway))
        val second = (supervisor.state.value as ConnectionState.Unreachable).nextRetryAt!! - testScheduler.currentTime
        assertTrue("was $second", second in 1_000..2_000)
    }

    @Test
    fun aFallbackThatArrivesDuringABackoffIsUsedAtOnce() = runTest {
        fallbacks.value = emptyMap()
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()
        assertTrue(supervisor.state.value is ConnectionState.Unreachable)

        fallbacks.value = mapOf("local-1" to gateway)
        runCurrent()

        connectedVia(supervisor.state.value, fallback = true)
    }

    @Test
    fun aWithdrawnFallbackStopsBeingUsed() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        fallbacks.value = emptyMap()
        runCurrent()

        assertEquals("unreachable", (supervisor.state.value as ConnectionState.Unreachable).error)
        assertEquals(1, descriptorReads(gateway))
    }

    /** The stream dropped, so the run is pausing to retry the fallback it still holds. */
    @Test
    fun aFallbackWithdrawnDuringARetryPauseIsNeverSentTheTokenAgain() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()
        assertEquals(1, run.routes.size)

        run.emitError(T3Exception("stream_timeout"))
        runCurrent()
        assertTrue(supervisor.state.value is ConnectionState.Unreachable)

        fallbacks.value = emptyMap()
        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertEquals("no second attempt at the bearer", 1, run.routes.size)
        assertEquals("nor at its descriptor", 1, descriptorReads(gateway))
        assertNull(supervisor.fallback.value.inUse)
        assertEquals("unreachable", (supervisor.state.value as ConnectionState.Unreachable).error)
    }

    /** Pausing on another code path: a fallback whose reconnect failed before it connected. */
    @Test
    fun aFallbackWithdrawnWhileReconnectingIsDroppedBeforeTheToken() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        gatewayAnswer = {
            fallbacks.value = emptyMap()
            descriptor
        }
        run.emitError(T3Exception("stream_timeout"))
        advanceTimeBy(1_100)
        runCurrent()

        assertEquals("withdrawn after its descriptor, before the token", 1, run.routes.size)
    }

    @Test
    fun aHomeServerThatRefusesKeepsItsOwnRoute() = runTest {
        lanAnswer = { throw T3Exception("http_500") }
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()

        assertEquals("http_500", (supervisor.state.value as ConnectionState.Unreachable).error)
        assertEquals(0, descriptorReads(gateway))
    }

    @Test
    fun aRowWithoutAKnownEnvironmentNeverFallsBack() = runTest {
        val supervisor = supervisor(environmentId = null)
        supervisor.start()
        runCurrent()

        assertTrue(supervisor.state.value is ConnectionState.Unreachable)
        assertEquals(0, descriptorReads(gateway))
    }

    @Test
    fun aDroppedFallbackReconnectsOnTheFallback() = runTest {
        val supervisor = supervisor()
        supervisor.start()
        runCurrent()
        val before = descriptorReads(lan)

        run.emitError(T3Exception("stream_timeout"))
        runCurrent()
        assertEquals(before, descriptorReads(lan))
        advanceTimeBy(1_100)
        runCurrent()

        connectedVia(supervisor.state.value, fallback = true)
        assertFalse(run.routes.any { it == ConnectRoute.Bearer(lan, token) })
    }
}
