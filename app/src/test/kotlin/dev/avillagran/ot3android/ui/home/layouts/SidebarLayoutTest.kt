package dev.avillagran.ot3android.ui.home.layouts

import dev.avillagran.ot3android.core.home.*
import dev.avillagran.ot3android.core.shell.RowTime
import org.junit.Assert.*
import org.junit.Test

internal fun sidebarFixture(): List<HomeRow> = listOf(
    fixtureRow("node", "pin", pinned = true), fixtureRow("mac", "input", state = "waiting"),
    fixtureRow("node", "run", state = "working"), fixtureRow("mac", "sleep", snoozed = true),
    fixtureRow("node", "old", settled = true), fixtureRow("mac", "ready", state = "done"),
    fixtureRow("node", "same"), fixtureRow("mac", "same"),
)
internal fun fixtureRow(host: String, id: String, state: String = "idle", pinned: Boolean = false,
    snoozed: Boolean = false, settled: Boolean = false) = HomeRow(
    key = "$host/$id", environmentId = host, threadId = id,
    title = "A very long thread title that needs room on a phone — $id", projectId = "app",
    projectKey = "$host/app", projectTitle = "Project with a very long repository name", environmentLabel = host,
    branch = "feat/sidebar-layouts", path = null, worktree = false, detail = state, state = state,
    statusLabel = state, statusRole = state, time = RowTime("age", 0, "3m"), pinned = pinned,
    settled = settled, snoozed = snoozed, working = state == "working", depth = 0, subagent = false,
    identity = dev.avillagran.ot3android.core.shell.ProjectIdentity(key = "$host/app", monogram = "O3", label = "O3", kind = "monogram", icon = null, colorSlot = 0, colorKey = "blue", color = "#5aa2e6", fill = .16), canSnooze = true, autoSettleDisabled = false, style = if (settled || snoozed) RowStyle.Slim else RowStyle.Card, swipe = SwipeActions.None,
)
class SidebarLayoutTest {
    @Test fun nestedRowsFollowStableParentIdentityAcrossHostsAndMapOrder() {
        val root = fixtureRow("node", "root")
        val child = fixtureRow("node", "child").copy(parentKey = root.key, subagent = true)
        val nested = fixtureRow("node", "nested").copy(parentKey = child.key, subagent = true)
        val other = fixtureRow("mac", "root")
        val rows = nestedSidebarRows(listOf(nested, other, child, root))
        assertEquals(listOf(other.key, root.key, child.key, nested.key), rows.map { it.key })
        assertEquals(listOf(0, 0, 1, 2), rows.map { it.depth })
        val sections = sidebarSections(rows, "projects", true, SidebarMetadata(), false)
        assertEquals(listOf(root.key, child.key, nested.key), sections.single { it.rows.firstOrNull()?.key == root.key }.rows.map { it.key })
    }

    @Test fun groupingSeparatesPinsInputAndShelvesWithoutDuplicates() {
        val rows = sidebarFixture()
        val sections = sidebarSections(rows, "classic", true, SidebarMetadata(), false)
        assertEquals(listOf("pinned", "idle", "done", "working", "waiting", "snoozed", "settled"), sections.map { it.key })
        assertEquals(rows.map { it.key }.toSet(), sections.flatMap { it.rows }.map { it.key }.toSet())
    }
    @Test fun equalReplyClocksTieByThreadThenHostAndActiveClockDoesNotBuryRun() {
        val rows = listOf(fixtureRow("node", "same"), fixtureRow("mac", "same"), fixtureRow("mac", "run", "working"))
        val metadata = SidebarMetadata(clocks = rows.associate { it.key to ReplyClock(repliedAt = 10, updatedAt = if (it.working) 20 else 10) })
        assertEquals(listOf("mac/run", "mac/same", "node/same"), sidebarSections(rows, "classic", false, metadata, true).single().rows.map { it.key })
    }
    @Test fun missingClockPreservesNativeOrder() {
        val rows = sidebarFixture()
        assertEquals(rows, sidebarSections(rows, "classic", false, SidebarMetadata(), true).single().rows)
    }
    @Test fun projectTreeNeverCombinesEqualProjectIdsAcrossHosts() {
        val sections = sidebarSections(sidebarFixture(), "projects", true, SidebarMetadata(), false)
        assertEquals(2, sections.count { it.rows.isEmpty() })
        assertTrue(sections.filter { it.rows.isNotEmpty() }.all { it.rows.map { r -> r.environmentId }.distinct().size == 1 })
    }
    @Test fun chipsCountOnlyOpenParentsAndThreadGroupOverridesProjectGroup() {
        val rows = sidebarFixture()
        val metadata = SidebarMetadata(threadGroups = mapOf("node/pin" to "Review"), projectGroups = mapOf("node/app" to "Android"))
        assertEquals("Review", metadata.group(rows.first()))
        assertEquals(1, sidebarChips(rows, metadata).single { it.key == "group:Review" }.count)
        assertEquals(3, sidebarChips(rows, metadata).single { it.key == "node/app" }.count)
    }
    @Test fun focusUsesFourTaskBucketsAndKeepsHostIdentity() {
        val sections = sidebarSections(sidebarFixture(), "focus", true, SidebarMetadata(), true)
        assertEquals(listOf("pending", "working", "waiting", "done"), sections.map { it.title })
        assertEquals(8, sections.sumOf { it.rows.size })
    }
}
