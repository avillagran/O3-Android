package dev.avillagran.ot3android.core.shell

import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SidebarView] over the real lib (`sidebar.view` through the host JNI library). The
 * multi-environment vectors are lib-t3-rust's own `tests/fixtures/sidebar-environments.json`
 * (v0.3.3), with the expectations of its `tests/sidebar_view.rs`; every shell goes through
 * [ShellReducer] first, as in the app.
 */
class SidebarViewTest {

    private val domain = DomainEngine.shared

    @Before
    fun setUp() = assumeHostJni()

    private fun libFixture(): Pair<List<SidebarEnvironment>, Long> {
        val text = javaClass.getResource("/lib-t3-rust/sidebar-environments.json")!!.readText()
        val fixture = JSONObject(text)
        val array = fixture.getJSONArray("environments")
        val environments = (0 until array.length()).map { i ->
            val e = array.getJSONObject(i)
            SidebarEnvironment(
                id = e.getString("id"),
                name = e.optString("name").ifEmpty { null },
                shell = e.optJSONObject("shell")?.let(ShellReducer::parse),
                state = if (e.isNull("state")) null else e.optString("state").ifEmpty { null },
            )
        }
        return environments to fixture.getLong("nowMs")
    }

    private fun Sidebar.all() = active + working + snoozed + settled

    @Test
    fun severalEnvironmentsMergeWithTheLibsDetailsLabelsAndRoles() = runBlocking {
        val (environments, now) = libFixture()

        val sidebar = SidebarView.build(domain, environments, now)

        assertEquals(
            listOf(
                SidebarEnvironmentState("laptop", "Laptop", "ready"),
                SidebarEnvironmentState("desktop", "Desktop", "ready"),
                SidebarEnvironmentState("offline", "Offline", "unreachable"),
            ),
            sidebar.environments,
        )
        assertEquals(mapOf("done" to 1, "failed" to 1, "waiting" to 3, "working" to 2), sidebar.counts)
        val rows = sidebar.all().associateBy { it.threadId }
        for ((id, detail, label, role) in listOf(
            listOf("l-request", "approval", "Approval", "warning"),
            listOf("l-question", "input", "Input", "input"),
            listOf("l-plan", "plan", "Plan ready", "waiting"),
            listOf("d-goal", "goal", "Goal", "working"),
            listOf("l-working", "working", "Working", "working"),
            listOf("l-failed", "failed", "Failed", "failed"),
            listOf("l-review", "done", "Done", "done"),
            listOf("d-background", "waiting", "Waiting", "muted"),
            listOf("d-seen", "", "", "muted"),
        )) {
            val row = rows.getValue(id)
            assertEquals(id, listOf(detail, label, role), listOf(row.detail, row.statusLabel, row.statusRole))
        }
        assertEquals(RowTime("elapsed", now - 125_000, "2m 5s"), rows.getValue("l-working").time)
        assertEquals(RowStatus.NeedsYou, rows.getValue("l-request").status)
        assertEquals(RowStatus.Working, rows.getValue("l-working").status)
        assertEquals(RowStatus.Failed, rows.getValue("l-failed").status)
        assertEquals(RowStatus.Done, rows.getValue("l-review").status)
        assertEquals(RowStatus.Quiet, rows.getValue("d-seen").status)
        assertFalse("archived rows stay out", "l-archived" in rows)
    }

    @Test
    fun rowsCarryKeysProjectsAndChips() = runBlocking {
        val (environments, now) = libFixture()

        val sidebar = SidebarView.build(domain, environments, now)

        val failed = sidebar.all().first { it.threadId == "l-failed" }
        assertEquals("laptop/l-failed", failed.key)
        assertEquals("laptop", failed.environmentId)
        assertEquals("laptop/p-docs", failed.projectKey)
        assertEquals("docs site", failed.projectTitle)
        val chip = sidebar.identity(failed)!!
        assertEquals("DS", chip.monogram)
        assertEquals("monogram", chip.kind)
        assertTrue(chip.colorSlot != null && chip.colorKey != null)
        assertTrue("every project has a chip", sidebar.all().all { it.projectKey == null || sidebar.identity(it) != null })
        assertTrue(sidebar.snoozed.any { it.threadId == "l-snoozed" && it.snoozed })
        assertTrue(sidebar.settled.all { it.settled })
    }

    @Test
    fun theEnvironmentFilterKeepsOneEnvironment() = runBlocking {
        val (environments, now) = libFixture()

        val sidebar = SidebarView.build(domain, environments, now, environmentId = "desktop")

        assertTrue(sidebar.all().isNotEmpty())
        assertTrue(sidebar.all().all { it.key.startsWith("desktop/") })
        assertEquals(7, sidebar.raw.getJSONObject("filterCounts").getInt("all"))
    }

    @Test
    fun oneShellAsTheAppHoldsIt() = runBlocking {
        val shell = ShellReducer.parse(
            ShellFixtures.body(
                5,
                listOf(ShellFixtures.project("p1", "Alpha"), ShellFixtures.project("p2", "Beta")),
                listOf(
                    ShellFixtures.thread("old", "p1", "updatedAt" to "2026-10-24T08:00:00.000Z"),
                    ShellFixtures.thread("new", "p2", "updatedAt" to "2026-10-24T11:00:00.000Z", "createdAt" to "2026-10-24T11:00:00.000Z"),
                    ShellFixtures.thread("pin", "p1", "pinnedAt" to "2026-10-20T00:00:00.000Z"),
                    ShellFixtures.thread("done", "p1", "settledAt" to "2026-10-23T00:00:00.000Z"),
                    ShellFixtures.thread("gone", "p1", "archivedAt" to "2026-10-23T00:00:00.000Z"),
                    ShellFixtures.thread("del", "p1", "deletedAt" to "2026-10-23T00:00:00.000Z"),
                    ShellFixtures.thread("sub", "p1", "lineage" to JSONObject().put("relationshipToParent", "subagent")),
                ),
            ),
        )

        val sidebar = SidebarView.build(domain, listOf(SidebarEnvironment("env-1", "Workstation", shell)), NOW, "env-1")

        assertEquals(listOf("pin", "new", "old"), sidebar.active.map { it.threadId })
        assertTrue(sidebar.active.first().pinned)
        assertEquals(listOf("done"), sidebar.settled.map { it.threadId })
        assertEquals(setOf("env-1/p1", "env-1/p2"), sidebar.identities.keys)
        assertEquals("Alpha", sidebar.active.last().projectTitle)
    }

    @Test
    fun aResolvedRepositoryIdentityReachesTheLib() = runBlocking {
        // The identity arrives later on the stream (metadata refresh); the reducer keeps it,
        // and the lib groups both checkouts of one repository.
        var shell = ShellReducer.parse(
            ShellFixtures.body(
                5,
                listOf(ShellFixtures.project("p1", "app"), ShellFixtures.project("p2", "app copy")),
                listOf(ShellFixtures.thread("t1", "p1"), ShellFixtures.thread("t2", "p2")),
            ),
        )
        shell = shell.copy(projects = shell.projects.map { it.copy(repositoryIdentity = """{"canonicalKey":"github.com/x/app"}""") })

        val sidebar = SidebarView.build(domain, listOf(SidebarEnvironment("env-1", null, shell)), NOW)

        val groups = sidebar.raw.getJSONArray("projectGroups")
        assertEquals(1, groups.length())
        assertEquals("repository/github.com/x/app", groups.getJSONObject(0).getString("key"))
    }

    @Test
    fun aShellTheLibCannotReadIsMalformedNotAnException() = runBlocking {
        val shell = ShellReducer.parse(
            ShellFixtures.body(1, emptyList(), listOf(ShellFixtures.thread("t1").put("title", JSONObject.NULL))),
        )

        val sidebar = SidebarView.build(domain, listOf(SidebarEnvironment("env-1", null, shell)), NOW)

        assertEquals("malformed", sidebar.environments.single().state)
        assertTrue(sidebar.isEmpty)
    }

    private companion object {
        /** 2026-10-24T12:00:00Z. */
        const val NOW = 1_792_843_200_000L
    }
}
