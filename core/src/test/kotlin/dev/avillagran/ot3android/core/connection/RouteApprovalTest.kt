package dev.avillagran.ot3android.core.connection

import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.InMemoryEnvironmentDao
import dev.avillagran.ot3android.core.data.InMemoryTokenStore
import dev.avillagran.ot3android.core.shell.ShellFixtures.body
import dev.avillagran.ot3android.core.shell.ShellFixtures.project
import dev.avillagran.ot3android.core.shell.ShellFixtures.thread
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.FakeT3TransportFactory
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The whole opt-in, from the catalog's offer to the bearer leaving. */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteApprovalTest {
    private val lan = "http://192.168.1.177:3773"
    private val gateway = "https://gateway.example:39670/"
    private val origin = "https://gateway.example:39670"
    private val descriptor = JSONObject().put("environmentId", "env-1")
    private val cursors = MemoryCursorStore()
    private val tokens = InMemoryTokenStore()
    private val approvals = InMemoryRouteApprovals()
    private val factory = FakeT3TransportFactory {
        FakeT3Transport().apply {
            onFetchDescriptor(lan) { throw T3Exception("unreachable") }
            for (url in listOf(gateway, "${gateway}t3/", "https://gateway.example:1/", "https://other.example:39670/")) {
                onFetchDescriptor(url) { descriptor }
            }
            onShell { body(5, listOf(project("p1")), listOf(thread("t1"))) }
        }
    }

    private val bearers get() = factory.created.flatMap { it.routes }
    private fun contacted(url: String) =
        factory.created.flatMap { it.log }.any { it.method == "fetchDescriptor" && it.args.first() == url }

    private suspend fun TestScope.manager(): Pair<ConnectionManager, ConnectionSupervisor> {
        val repository = EnvironmentRepository(InMemoryEnvironmentDao(), { tokens }, cursors, newId = { "row" })
        repository.save(null, "NODE", lan, "env-1", "token-12345678")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = ConnectionManager(
            repository, cursors, factory, backgroundScope, clock = { testScheduler.currentTime }, random = Random(3),
            io = dispatcher, computation = dispatcher, routeApprovals = approvals,
        )
        return manager to manager.supervisor("row")!!
    }

    @Test
    fun noBearerLeavesWithoutApproval() = runTest {
        val (manager, supervisor) = manager()
        manager.setRouteFallbacks(mapOf("row" to gateway))
        supervisor.start()
        runCurrent()
        testScheduler.advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertTrue("nothing at all, not even a descriptor read", !contacted(gateway))
        assertTrue(bearers.isEmpty())
        assertEquals(mapOf("row" to RouteFallbackView.NeedsApproval(origin)), manager.routeFallbackViews.first())
    }

    @Test
    fun approvalOpensTheRouteAtOnce() = runTest {
        val (manager, supervisor) = manager()
        manager.setRouteFallbacks(mapOf("row" to gateway))
        supervisor.start()
        runCurrent()

        manager.approveRoute("row", origin)
        runCurrent()

        assertEquals(listOf(ConnectRoute.Bearer(gateway, "token-12345678")), bearers)
        assertEquals(mapOf("row" to origin), approvals.all())
        assertEquals(mapOf("row" to RouteFallbackView.InUse(origin)), manager.routeFallbackViews.first())
    }

    @Test
    fun approvalIsBoundToTheExactOrigin() = runTest {
        val (manager, supervisor) = manager()
        manager.setRouteFallbacks(mapOf("row" to gateway))
        manager.approveRoute("row", origin)
        supervisor.start()
        runCurrent()
        assertEquals(1, bearers.size)

        for (moved in listOf("https://gateway.example:1/", "https://other.example:39670/")) {
            manager.setRouteFallbacks(mapOf("row" to moved))
            runCurrent()
            testScheduler.advanceTimeBy(10 * 60_000L)
            runCurrent()

            assertEquals("$moved gets no token", 1, bearers.size)
            assertTrue(!contacted(moved))
            assertEquals(
                mapOf("row" to RouteFallbackView.NeedsApproval(routeOrigin(moved)!!)),
                manager.routeFallbackViews.first(),
            )
        }
    }

    @Test
    fun aPathChangeAtTheSameOriginKeepsTheApproval() = runTest {
        val (manager, supervisor) = manager()
        manager.setRouteFallbacks(mapOf("row" to "${gateway}t3/"))
        manager.approveRoute("row", origin)
        supervisor.start()
        runCurrent()
        assertEquals(listOf(ConnectRoute.Bearer("${gateway}t3/", "token-12345678")), bearers)
    }

    @Test
    fun anApprovalBelongsToOneRow() = runTest {
        val (manager, supervisor) = manager()
        manager.setRouteFallbacks(mapOf("row" to gateway))
        manager.approveRoute("another-row", origin)
        supervisor.start()
        runCurrent()
        assertTrue(bearers.isEmpty())
    }

    @Test
    fun anApprovalSurvivesARestartAndNothingElseDoes() = runTest {
        approvals.set("row", origin)
        val (manager, supervisor) = manager()
        manager.setRouteFallbacks(mapOf("row" to gateway))
        supervisor.start()
        runCurrent()
        assertTrue("not loaded yet, so closed", bearers.isEmpty())

        manager.restoreRouteApprovals()
        runCurrent()
        assertEquals(1, bearers.size)
    }

    @Test
    fun stoppingTheRouteTakesTheApprovalBack() = runTest {
        val (manager, supervisor) = manager()
        manager.setRouteFallbacks(mapOf("row" to gateway))
        manager.approveRoute("row", origin)
        supervisor.start()
        runCurrent()

        manager.revokeRoute("row")
        runCurrent()
        testScheduler.advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertEquals(1, bearers.size)
        assertEquals(emptyMap<String, String>(), approvals.all())
        assertEquals(mapOf("row" to RouteFallbackView.NeedsApproval(origin)), manager.routeFallbackViews.first())
    }

    @Test
    fun forgettingARowForgetsItsApproval() = runTest {
        val (manager, _) = manager()
        manager.approveRoute("row", origin)
        manager.forget("row")
        assertEquals(emptyMap<String, String>(), approvals.all())
    }

    @Test
    fun aRefusedTokenIsShownOnTheRowWithItsOrigin() = runTest {
        val factory = this@RouteApprovalTest.factory
        val (manager, supervisor) = manager()
        manager.setRouteFallbacks(mapOf("row" to gateway))
        manager.approveRoute("row", origin)
        supervisor.start()
        runCurrent()
        factory.created.first().emitError(T3Exception("unauthorized"))
        runCurrent()

        assertEquals(mapOf("row" to RouteFallbackView.Refused(origin, "unauthorized")), manager.routeFallbackViews.first())
    }

    @Test
    fun nothingIsAskedWhileTheOwnUrlAnswers() {
        val offered = routeFallbackView(gateway, null, FallbackStatus(ownRouteDown = false))
        assertNull(offered)
    }

    @Test
    fun anOriginIsSchemeHostAndPort() {
        assertEquals("https://gateway.example:443", routeOrigin("https://GATEWAY.example/"))
        assertEquals("https://gateway.example:443", routeOrigin("https://gateway.example:443/a/b"))
        assertEquals("https://gateway.example:8443", routeOrigin("HTTPS://gateway.example:8443"))
        assertEquals("gateway.example", routeOriginLabel(routeOrigin("https://gateway.example/")!!))
        assertEquals("gateway.example:8443", routeOriginLabel(routeOrigin("https://gateway.example:8443/")!!))
    }

    @Test
    fun anOriginAPersonCouldMisreadIsNotOne() {
        for (url in listOf(
            "http://gateway.example/", "https://good.example@evil.example/", "https://user:pw@gateway.example/",
            "https://gаteway.example/", "https://", "not a url", "",
        )) assertNull(url, routeOrigin(url))
    }
}
