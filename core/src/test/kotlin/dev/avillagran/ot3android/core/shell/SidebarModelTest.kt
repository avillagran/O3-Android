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

    private val now = ShellReducer.parseTimestamp("2026-10-25T12:00:00.000Z")!!

    private fun build(shell: ShellState, grouped: Boolean = false) = SidebarModel.sections(SidebarModel.rows("e1", shell, now), grouped)

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
        val sidebar = build(
            shell(
                thread("visible"),
                thread("archived", "p1", "archivedAt" to "2026-10-24T09:00:00.000Z"),
                thread("deleted", "p1", "deletedAt" to "2026-10-24T09:00:00.000Z"),
                thread("sub", "p1", "lineage" to JSONObject().put("parentThreadId", "visible").put("relationshipToParent", "subagent")),
                thread("fork", "p1", "lineage" to JSONObject().put("parentThreadId", "visible").put("relationshipToParent", "fork")),
            ),
        )

        assertEquals(listOf("visible", "fork"), sidebar.active.map { it.thread.id }.sortedDescending())
        assertTrue(sidebar.settled.isEmpty())
    }

    @Test
    fun pinnedFirstByOrderKeyThenTheRestNewestFirst() {
        val sidebar = build(
            shell(
                thread("old", "p1", "createdAt" to "2026-10-20T00:00:00.000Z"),
                thread("new", "p1", "createdAt" to "2026-10-24T00:00:00.000Z"),
                thread("pinB", "p1", "pinnedAt" to "2026-10-01T00:00:00.000Z", "pinOrderKey" to "b"),
                thread("pinA", "p1", "pinnedAt" to "2026-10-02T00:00:00.000Z", "pinOrderKey" to "a"),
                thread("pinA2", "p1", "pinnedAt" to "2026-10-03T00:00:00.000Z", "pinOrderKey" to "a"),
            ),
        )

        val rows = sidebar.active
        assertEquals(listOf("pinA", "pinA2", "pinB", "new", "old"), rows.map { it.thread.id })
        assertEquals(listOf(true, true, true, false, false), rows.map { it.pinned })
    }

    @Test
    fun rowsOfEveryProjectShareOneListAndSettledIsSeparate() {
        val sidebar = build(
            shell(
                thread("a1", "p1", "createdAt" to "2026-10-20T00:00:00.000Z"),
                thread("b1", "p2", "createdAt" to "2026-10-23T00:00:00.000Z"),
                thread("x1", "gone", "createdAt" to "2026-10-21T00:00:00.000Z"),
                thread("s-old", "p1", "settledAt" to "2026-10-10T00:00:00.000Z"),
                thread("s-new", "p2", "settledAt" to "2026-10-22T00:00:00.000Z"),
            ),
        )

        assertEquals(listOf("b1", "x1", "a1"), sidebar.active.map { it.thread.id })
        assertEquals(listOf("s-new", "s-old"), sidebar.settled.map { it.thread.id })
        assertEquals(listOf("e1/b1", "e1/x1", "e1/a1"), sidebar.active.map { it.key })
    }

    @Test
    fun groupedFoldsUnpinnedWorkingRowsIntoTheWorkingShelf() {
        val shell = shell(
            thread("run", "p1", "activeRunId" to "r1", "latestUserMessageAt" to "2026-10-24T00:00:00.000Z"),
            thread("run-old", "p1", "activeRunId" to "r2", "latestUserMessageAt" to "2026-10-23T00:00:00.000Z"),
            thread("run-pinned", "p1", "activeRunId" to "r3", "pinnedAt" to "2026-10-01T00:00:00.000Z"),
            thread("ask", "p1", "pendingRuntimeRequest" to JSONObject().put("kind", "approval"), "activeRunId" to "r4"),
            thread("idle", "p1"),
        )

        val flat = build(shell, grouped = false)
        assertTrue(flat.working.isEmpty())
        assertEquals(5, flat.active.size)

        val grouped = build(shell, grouped = true)
        assertEquals(listOf("run", "run-old"), grouped.working.map { it.thread.id })
        assertEquals("pinned work and requests stay in the inbox", listOf("run-pinned", "ask", "idle").sorted(), grouped.active.map { it.thread.id }.sorted())
        assertEquals("run-pinned", grouped.active.first().thread.id)
    }

    @Test
    fun snoozeFollowsTheLibsRule() {
        val until = "2026-10-25T15:00:00.000Z"
        val at = "2026-10-25T11:00:00.000Z"
        assertTrue(SidebarModel.isSnoozed(row("snoozedUntil" to until, "snoozedAt" to at), now))
        assertFalse("expired", SidebarModel.isSnoozed(row("snoozedUntil" to "2026-10-25T11:59:00.000Z"), now))
        assertFalse("a request raises it", SidebarModel.isSnoozed(row("snoozedUntil" to until, "pendingRuntimeRequest" to JSONObject()), now))
        assertFalse("approvals raise it", SidebarModel.isSnoozed(row("snoozedUntil" to until, "hasPendingApprovals" to true), now))
        assertFalse(
            "a run completed after the snooze raises it",
            SidebarModel.isSnoozed(row("snoozedUntil" to until, "snoozedAt" to at, "latestRun" to JSONObject().put("state", "completed").put("completedAt", "2026-10-25T11:30:00.000Z")), now),
        )
        assertTrue(
            "an error from before the snooze does not",
            SidebarModel.isSnoozed(row("snoozedUntil" to until, "snoozedAt" to at, "runtime" to JSONObject().put("status", "error").put("updatedAt", "2026-10-25T10:00:00.000Z")), now),
        )
        assertFalse(
            "an error after the snooze does",
            SidebarModel.isSnoozed(row("snoozedUntil" to until, "snoozedAt" to at, "session" to JSONObject().put("status", "failed").put("updatedAt", "2026-10-25T11:10:00.000Z")), now),
        )
        val sidebar = build(shell(thread("s", "p1", "snoozedUntil" to until), thread("settled-snoozed", "p1", "snoozedUntil" to until, "settledOverride" to "settled"), thread("a")))
        assertEquals("snoozed rows leave every other section", listOf("s", "settled-snoozed").sorted(), sidebar.snoozed.map { it.thread.id }.sorted())
        assertEquals(listOf("a"), sidebar.active.map { it.thread.id })
        assertTrue(sidebar.settled.isEmpty())
    }

    @Test
    fun runStartAndWorktreeComeFromTheRow() {
        assertEquals(
            ShellReducer.parseTimestamp("2026-10-25T11:59:00.000Z"),
            SidebarModel.runStartedAt(row("latestRunStartedAt" to "2026-10-25T11:00:00.000Z", "activityRunStartedAt" to "2026-10-25T11:59:00.000Z")),
        )
        assertEquals(null, SidebarModel.runStartedAt(row()))
        assertEquals("/w/tree", SidebarModel.worktreePath(row("worktreePath" to "/w/tree")))
        assertEquals(null, SidebarModel.worktreePath(row("worktreePath" to null)))
    }

    @Test
    fun rowStatus() {
        assertEquals(RowStatus.NeedsYou, SidebarModel.status(row("pendingRuntimeRequest" to JSONObject().put("kind", "approval"), "status" to "running")))
        assertEquals(RowStatus.NeedsYou, SidebarModel.status(row("pendingRuntimeRequest" to JSONObject().put("kind", "user_input").put("blocking", true))))
        assertEquals(
            "an async question does not hold the thread",
            RowStatus.Working,
            SidebarModel.status(row("pendingRuntimeRequest" to JSONObject().put("kind", "user_input").put("blocking", false), "status" to "running")),
        )
        assertEquals(RowStatus.Quiet, SidebarModel.status(row("pendingRuntimeRequest" to JSONObject().put("kind", "auth_refresh"))))
        assertEquals(RowStatus.Working, SidebarModel.status(row("activeRunId" to "run-1")))
        assertEquals(RowStatus.Working, SidebarModel.status(row("status" to "running")))
        assertEquals(RowStatus.Failed, SidebarModel.status(row("status" to "failed")))
        assertEquals(RowStatus.Failed, SidebarModel.status(row("status" to "error")))
        assertEquals("lastError alone is not a failure", RowStatus.Quiet, SidebarModel.status(row("lastError" to "boom")))
        assertEquals(RowStatus.Quiet, SidebarModel.status(row("status" to "completed")))
        assertEquals(RowStatus.Quiet, SidebarModel.status(row("status" to "some-future-status")))
    }

    @Test
    fun activeRowsFollowTheInboxAnchorThenTheActiveOrderKeyNeverUpdatedAt() {
        val sidebar = build(
            shell(
                thread("keyB", "p1", "activeOrderKey" to "b"),
                thread("keyA", "p1", "activeOrderKey" to "a"),
                // updatedAt moves on metadata writes: it must not lift a row.
                thread("touched", "p1", "createdAt" to "2026-10-01T00:00:00.000Z", "updatedAt" to "2026-10-30T00:00:00.000Z"),
                thread("ran", "p1", "createdAt" to "2026-10-01T00:00:00.000Z", "latestRunCompletedAt" to "2026-10-20T00:00:00.000Z"),
                thread("asked", "p1", "createdAt" to "2026-10-01T00:00:00.000Z", "latestRunRequestedAt" to "2026-10-22T00:00:00.000Z"),
                thread("reopened", "p1", "createdAt" to "2026-10-01T00:00:00.000Z", "unsettledAt" to "2026-10-21T00:00:00.000Z"),
            ),
        )

        assertEquals(
            listOf("asked", "reopened", "ran", "touched", "keyA", "keyB"),
            sidebar.active.map { it.thread.id },
        )
    }

    @Test
    fun settledRowsSortBySettledThenLastRunThenLastMessageThenUpdated() {
        val sidebar = build(
            shell(
                thread("settled", "p1", "settledOverride" to "settled", "settledAt" to "2026-10-10T00:00:00.000Z"),
                thread("run", "p1", "settledOverride" to "settled", "latestRunCompletedAt" to "2026-10-12T00:00:00.000Z"),
                thread("message", "p1", "settledOverride" to "settled", "latestUserMessageAt" to "2026-10-11T00:00:00.000Z"),
                thread("updated", "p1", "settledOverride" to "settled", "updatedAt" to "2026-10-09T00:00:00.000Z"),
            ),
        )

        assertEquals(listOf("run", "message", "settled", "updated"), sidebar.settled.map { it.thread.id })
    }
}
