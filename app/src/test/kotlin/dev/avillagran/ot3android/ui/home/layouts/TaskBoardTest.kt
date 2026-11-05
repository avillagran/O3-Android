package dev.avillagran.ot3android.ui.home.layouts

import org.junit.Assert.*
import org.junit.Test

class TaskBoardTest {
    @Test fun columnsFollowDesktopStatesAndExcludeSubagents() {
        val rows = listOf(fixtureRow("node", "pending"), fixtureRow("node", "working", "working"),
            fixtureRow("node", "waiting", "waiting"), fixtureRow("node", "failed", "failed"),
            fixtureRow("node", "done", "done"), fixtureRow("node", "closed", settled = true),
            fixtureRow("node", "snoozed").copy(snoozed = true),
            fixtureRow("node", "child").copy(subagent = true))
        val columns = taskBoardColumns(rows, SidebarMetadata(), null).associate { it.status to it.rows.map { row -> row.threadId } }
        assertEquals(listOf("pending"), columns[TaskBoardStatus.Pending])
        assertEquals(listOf("working"), columns[TaskBoardStatus.Working])
        assertEquals(listOf("waiting"), columns[TaskBoardStatus.Waiting])
        assertEquals(listOf("done"), columns[TaskBoardStatus.Done])
        assertEquals(listOf("failed"), columns[TaskBoardStatus.Failed])
        assertEquals(listOf("closed"), columns[TaskBoardStatus.Closed])
        assertEquals(listOf("snoozed"), columns[TaskBoardStatus.Snoozed])
        val expected = rows.filterNot { it.subagent }.map { it.threadId }
        val actual = columns.values.flatten()
        assertEquals(expected.toSet(), actual.toSet())
        assertEquals(expected.size, actual.size)
    }
    @Test fun projectAndGroupFiltersKeepHostIdentityAndEmptyColumns() {
        val rows = listOf(fixtureRow("node", "same"), fixtureRow("mac", "same"))
        val metadata = SidebarMetadata(threadGroups = mapOf("mac/same" to "Review"))
        val byGroup = taskBoardColumns(rows, metadata, "group:Review")
        assertEquals(listOf("mac/same"), byGroup.flatMap { it.rows }.map { it.key })
        assertEquals(TaskBoardStatus.entries.size, byGroup.size)
        assertEquals(listOf("node/same"), taskBoardColumns(rows, metadata, "node/app").flatMap { it.rows }.map { it.key })
    }
    @Test fun replyClocksSortWithoutReadingLocalizedTimeLabels() {
        val older = fixtureRow("node", "older")
        val recent = fixtureRow("node", "recent")
        val metadata = SidebarMetadata(clocks = mapOf(older.key to ReplyClock(repliedAt = 1), recent.key to ReplyClock(repliedAt = 2)))
        assertEquals(listOf(recent, older), taskBoardColumns(listOf(older, recent), metadata, null).first().rows)
    }
}
