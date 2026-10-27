package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.shell.*
import dev.avillagran.ot3android.core.transport.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Cache policy consumes native sidebar membership; no settlement rules in the host. */
class ThreadCacheEligibilityTest {
    @get:Rule val root = TemporaryFolder()
    @Before fun native() = assumeHostJni()
    @Test fun nativeClosedAndArchivedRowsLoseOnlyTheirLocalProjection() = runBlocking {
        val shell = ShellReducer.parse(ShellFixtures.body(1, listOf(ShellFixtures.project("p", "Project")), listOf(
            ShellFixtures.thread("active", "p"),
            ShellFixtures.thread("closed", "p", "settledAt" to "2026-10-25T08:00:00Z"),
            ShellFixtures.thread("archived", "p", "archivedAt" to "2026-10-25T08:00:00Z"),
        )))
        val sidebar = SidebarView.build(TestDomain, listOf(SidebarEnvironment("env", shell = shell)), 1792972800000)
        assertTrue(sidebar.threads.single { it.threadId == "closed" }.settled)
        assertFalse(sidebar.threads.any { it.threadId == "archived" })
        val cache = ThreadCache(root.newFolder())
        val leases = listOf("active", "closed", "archived").associateWith { id ->
            cache.open("env", id, "server")!!.also { cache.write(it, JSONObject().put("snapshotSequence", 1).put("projection", JSONObject())) }
        }
        cache.reconcile("env", sidebar.threads.filterNot { it.settled }.map { it.threadId }.toSet())
        assertNotNull(cache.read(leases.getValue("active")))
        assertNull(cache.read(leases.getValue("closed")))
        assertNull(cache.read(leases.getValue("archived")))
    }
}
