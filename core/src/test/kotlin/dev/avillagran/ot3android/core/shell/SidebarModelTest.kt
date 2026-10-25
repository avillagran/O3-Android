package dev.avillagran.ot3android.core.shell

import dev.avillagran.ot3android.core.shell.ShellFixtures.project
import dev.avillagran.ot3android.core.shell.ShellFixtures.snapshot
import dev.avillagran.ot3android.core.shell.ShellFixtures.thread
import dev.avillagran.ot3android.core.transport.ThreadUpdates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarModelTest {

    private fun shell(vararg threads: JSONObject, projects: List<JSONObject> = listOf(project("p1", "Alpha"), project("p2", "Beta"))) =
        ShellReducer.reduce(null, ThreadUpdates.batch(snapshot(1, projects, threads.toList())))!!

    private fun row(vararg fields: Pair<String, Any?>) = shell(thread("t", "p1", *fields)).threads.single()

    @Test
    fun settledRule() {
        assertTrue(SidebarModel.isSettled(row("settledOverride" to "settled")))
        assertTrue(SidebarModel.isSettled(row("settledOverride" to true)))
        assertTrue(SidebarModel.isSettled(row("settledAt" to "2026-10-24T09:00:00.000Z")))
        assertFalse(SidebarModel.isSettled(row("settledAt" to "2026-10-24T09:00:00.000Z", "settledOverride" to "active")))
        assertFalse(SidebarModel.isSettled(row()))
        assertFalse(SidebarModel.isSettled(row("settledOverride" to false)))
    }

    @Test
    fun archivedDeletedAndSubagentThreadsAreHidden() {
        val sidebar = SidebarModel.build(
            shell(
                thread("visible"),
                thread("archived", "p1", "archivedAt" to "2026-10-24T09:00:00.000Z"),
                thread("deleted", "p1", "deletedAt" to "2026-10-24T09:00:00.000Z"),
                thread("sub", "p1", "lineage" to JSONObject().put("parentThreadId", "visible").put("relationshipToParent", "subagent")),
                thread("fork", "p1", "lineage" to JSONObject().put("parentThreadId", "visible").put("relationshipToParent", "fork")),
            ),
        )

        assertEquals(listOf("visible", "fork"), sidebar.active.flatMap { it.rows }.map { it.thread.id }.sortedDescending())
        assertTrue(sidebar.settled.isEmpty())
    }

    @Test
    fun pinnedFirstByOrderKeyThenTheRestNewestFirst() {
        val sidebar = SidebarModel.build(
            shell(
                thread("old", "p1", "updatedAt" to "2026-10-20T00:00:00.000Z"),
                thread("new", "p1", "updatedAt" to "2026-10-24T00:00:00.000Z"),
                thread("pinB", "p1", "pinnedAt" to "2026-10-01T00:00:00.000Z", "pinOrderKey" to "b"),
                thread("pinA", "p1", "pinnedAt" to "2026-10-02T00:00:00.000Z", "pinOrderKey" to "a"),
                thread("pinA2", "p1", "pinnedAt" to "2026-10-03T00:00:00.000Z", "pinOrderKey" to "a"),
            ),
        )

        val rows = sidebar.active.single().rows
        assertEquals(listOf("pinA", "pinA2", "pinB", "new", "old"), rows.map { it.thread.id })
        assertEquals(listOf(true, true, true, false, false), rows.map { it.pinned })
    }

    @Test
    fun activeIsGroupedByProjectMostRecentFirstAndSettledIsSeparate() {
        val sidebar = SidebarModel.build(
            shell(
                thread("a1", "p1", "updatedAt" to "2026-10-20T00:00:00.000Z"),
                thread("b1", "p2", "updatedAt" to "2026-10-23T00:00:00.000Z"),
                thread("x1", "gone", "updatedAt" to "2026-10-21T00:00:00.000Z"),
                thread("s-old", "p1", "settledAt" to "2026-10-10T00:00:00.000Z"),
                thread("s-new", "p2", "settledAt" to "2026-10-22T00:00:00.000Z"),
            ),
        )

        assertEquals(listOf("Beta", null, "Alpha"), sidebar.active.map { it.title })
        assertEquals(listOf("p2", "gone", "p1"), sidebar.active.map { it.projectId })
        assertEquals(listOf("s-new", "s-old"), sidebar.settled.map { it.thread.id })
    }

    @Test
    fun rowStatus() {
        assertEquals(RowStatus.NeedsYou, SidebarModel.status(row("pendingRuntimeRequest" to JSONObject().put("kind", "approval"), "status" to "running")))
        assertEquals(RowStatus.Working, SidebarModel.status(row("activeRunId" to "run-1")))
        assertEquals(RowStatus.Working, SidebarModel.status(row("status" to "running")))
        assertEquals(RowStatus.Failed, SidebarModel.status(row("status" to "failed")))
        assertEquals(RowStatus.Failed, SidebarModel.status(row("lastError" to "boom")))
        assertEquals(RowStatus.Quiet, SidebarModel.status(row("status" to "completed")))
        assertEquals(RowStatus.Quiet, SidebarModel.status(row("status" to "some-future-status")))
    }
}
