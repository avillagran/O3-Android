package dev.avillagran.ot3android.core.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ThreadActionsTest {

    private val row = HomeRow(
        key = "e1/t1", environmentId = "e1", threadId = "t1", title = "Fix", projectId = "p1", projectKey = "e1/p1",
        projectTitle = "Alpha", environmentLabel = null, branch = "main", path = "/w/alpha", status = RowStatusDetail.None,
        time = RowTime.None, pinned = false, settled = false, snoozed = false, chip = null, style = RowStyle.Card,
        swipe = SwipeActions.None,
    )

    private fun target(row: HomeRow = this.row, scoped: Boolean = false) = ThreadMenuTarget(row, projectScoped = scoped, working = false)

    private fun List<MenuEntry>.available(): List<ThreadAction> =
        filter { it.available }.flatMap { entry -> if (entry.children.isEmpty()) listOf(entry.action) else listOf(entry.action) + entry.children.filter { it.available }.map { it.action } }

    @Test
    fun interimSheetOffersOnlyCopyAndFilter() {
        val menu = ThreadActions.menu(target(), ActionContext.Interim)
        assertEquals(
            listOf(ThreadAction.FilterByProject, ThreadAction.Copy, ThreadAction.CopyPath, ThreadAction.CopyBranch, ThreadAction.CopyThreadId),
            menu.available(),
        )
        assertEquals(listOf(ThreadAction.AllProjects), ThreadActions.menu(target(scoped = true), ActionContext.Interim).available().take(1))
        val noBranch = ThreadActions.menu(target(row.copy(branch = null, path = null, projectKey = null)), ActionContext.Interim)
        assertEquals("no project, no path, no branch", listOf(ThreadAction.Copy, ThreadAction.CopyThreadId), noBranch.available())
        assertTrue("nothing that mutates", menu.filter { it.available }.none { it.action.mutates })
    }

    @Test
    fun fullMenuIsOt3sListInOrderGatedByCapabilities() {
        val everything = ActionContext(
            capabilities = ServerCapability.entries.toSet(),
            canWrite = true,
            buildersAvailable = true,
            newThreadAvailable = true,
            projectSettingsAvailable = true,
        )
        val menu = ThreadActions.menu(target().copy(canSnooze = true), everything)
        assertEquals(
            listOf(
                ThreadAction.NewThreadOnBranch, ThreadAction.Pin, ThreadAction.MarkSettled, ThreadAction.Snooze,
                ThreadAction.Rename, ThreadAction.RegenerateTitle, ThreadAction.MarkUnread, ThreadAction.FilterByProject,
                ThreadAction.AutoSettle, ThreadAction.AutoSettleEnabled, ThreadAction.AutoSettleDisabled,
                ThreadAction.Copy, ThreadAction.CopyPath, ThreadAction.CopyBranch, ThreadAction.CopyThreadId,
                ThreadAction.ProjectSettings, ThreadAction.Archive, ThreadAction.Delete,
            ),
            menu.available(),
        )
        val pinned = ThreadActions.menu(target(row.copy(pinned = true, settled = true, snoozed = true)), everything).available()
        assertTrue(listOf(ThreadAction.Unpin, ThreadAction.MoveUp, ThreadAction.MoveDown, ThreadAction.MoveToActive, ThreadAction.WakeNow).all { it in pinned })

        val noCaps = ThreadActions.menu(target(), everything.copy(capabilities = emptySet())).available()
        assertFalse(ThreadAction.Pin in noCaps)
        assertFalse(ThreadAction.MarkSettled in noCaps)
        assertFalse(ThreadAction.Snooze in noCaps)
        assertFalse(ThreadAction.AutoSettle in noCaps)
        assertTrue(ThreadAction.Rename in noCaps)

        val readOnly = ThreadActions.menu(target(), everything.copy(canWrite = false)).available()
        assertFalse(ThreadAction.Rename in readOnly)
        assertFalse(ThreadAction.Delete in readOnly)

        val working = ThreadActions.menu(target().copy(working = true), everything).available()
        assertFalse("ot3 never archives a working thread", ThreadAction.Archive in working)
    }

    @Test
    fun swipeFollowsT3Mobile() {
        val settlement = ActionContext(capabilities = setOf(ServerCapability.ThreadSettlement, ServerCapability.ThreadSnooze), canWrite = true, buildersAvailable = true)
        assertEquals(SwipeActions(SwipeAction.Settle, SwipeAction.Snooze, true), ThreadActions.swipe(row, settlement))
        assertEquals(SwipeAction.MoveToActive, ThreadActions.swipe(row.copy(settled = true), settlement).primary)
        assertEquals(SwipeActions(SwipeAction.Wake, null, true), ThreadActions.swipe(row.copy(snoozed = true), settlement))
        assertEquals(SwipeActions(SwipeAction.Archive, null, false), ThreadActions.swipe(row, ActionContext.Interim))
    }

    @Test
    fun projectMenuOffersOnlyCopyPathInterim() {
        val menu = ThreadActions.projectMenu("/w/alpha", ActionContext.Interim)
        assertEquals(listOf(ProjectAction.Rename, ProjectAction.GroupInto, ProjectAction.CopyPath, ProjectAction.ProjectSettings, ProjectAction.Remove), menu.map { it.action })
        assertEquals(listOf(ProjectAction.CopyPath), menu.filter { it.enabled }.map { it.action })
        assertTrue(ThreadActions.projectMenu(null, ActionContext.Interim).none { it.enabled })
    }

    @Test
    fun snoozePresetsFollowOt3() {
        val zone = TimeZone.getTimeZone("UTC")
        fun at(day: Int, hour: Int, minute: Int = 0) = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute) }.timeInMillis

        // Sunday 25 October 2026, 12:00.
        val sunday = ThreadActions.snoozePresets(at(25, 12), zone)
        assertEquals(
            listOf(
                SnoozePreset(SnoozeOption.InOneHour, at(25, 13)),
                SnoozePreset(SnoozeOption.InThreeHours, at(25, 15)),
                SnoozePreset(SnoozeOption.ThisEvening, at(25, 18)),
                SnoozePreset(SnoozeOption.Custom, null),
            ).map { it.option },
            sunday.map { it.option }.filter { it != SnoozeOption.Tomorrow && it != SnoozeOption.NextWeek },
        )
        assertEquals("tomorrow is Monday: no Next week", listOf(SnoozePreset(SnoozeOption.Tomorrow, at(26, 9))), sunday.filter { it.option == SnoozeOption.Tomorrow || it.option == SnoozeOption.NextWeek })

        // Wednesday 28 October 2026, 17:30: the evening is under an hour away.
        val wednesday = ThreadActions.snoozePresets(at(28, 17, 30), zone)
        assertFalse(wednesday.any { it.option == SnoozeOption.ThisEvening })
        assertEquals(SnoozePreset(SnoozeOption.NextWeek, Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.NOVEMBER, 2, 9, 0) }.timeInMillis), wednesday.first { it.option == SnoozeOption.NextWeek })
    }
}
