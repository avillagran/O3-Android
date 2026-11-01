package dev.avillagran.ot3android.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.avillagran.ot3android.core.home.*
import dev.avillagran.ot3android.core.shell.RowTime
import dev.avillagran.ot3android.ui.kit.DesktopThreadRow
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

abstract class ThreadActionsComposeContract {
    @get:Rule val rule = createComposeRule()
    private val row = HomeRow(
        key = "e1/t1", environmentId = "e1", threadId = "t1", title = "Fix", projectId = "p1", projectKey = "e1/p1",
        projectTitle = "Alpha", environmentLabel = null, branch = "main", path = "/w/alpha", worktree = false, detail = "", state = "idle",
        statusLabel = "", statusRole = "muted", time = RowTime("age", 0, "—"), pinned = false, settled = false, snoozed = false,
        working = false, depth = 0, subagent = false, identity = null, canSnooze = false, autoSettleDisabled = false,
        style = RowStyle.Card, swipe = SwipeActions.None,
    )


    @Test fun archiveConfirmationCannotSubmitWorkingThread() {
        var confirmed = 0
        rule.setContent { Ot3Theme { ThreadManagementDialog(row.copy(working = true), ThreadAction.Archive, {}, { confirmed++ }) } }
        rule.onNodeWithTag("thread_action_confirm").assertIsNotEnabled().performClick()
        assertEquals(0, confirmed)
    }

    @Test fun confirmationDispatchesOnlyOnceEvenBeforeDismissal() {
        var confirmed = 0
        rule.setContent { Ot3Theme { ThreadManagementDialog(row, ThreadAction.Delete, {}, { confirmed++ }) } }
        rule.onNodeWithTag("thread_action_confirm").performClick()
        rule.onNodeWithTag("thread_action_confirm").assertIsNotEnabled().performClick()
        assertEquals(1, confirmed)
    }

    @Test fun projectMenuKeepsUnavailableActionsVisible() {
        rule.setContent { Ot3Theme { ProjectActionsContent("Project", ThreadActions.projectMenu("/w", ActionContext.Interim)) {} } }
        rule.onNodeWithTag(SheetTags.project(ProjectAction.GroupInto)).assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithTag(SheetTags.project(ProjectAction.Remove)).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun menuOffersPinAndDisablesUnsupportedReorderAndReadOnlyMutations() {
        val actions = mutableListOf<ThreadAction>()
        val target = ThreadMenuTarget(row.copy(pinned = true), false, false)
        val menu = ThreadActions.menu(target, ActionContext(ServerCapability.entries.toSet(), true, true))
        rule.setContent { Ot3Theme { ThreadActionsContent(target.row, menu) { actions += it } } }
        rule.onNodeWithTag(SheetTags.action(ThreadAction.Unpin)).assertIsEnabled().performClick()
        assertEquals(listOf(ThreadAction.Unpin), actions)
        rule.onNodeWithTag(SheetTags.action(ThreadAction.MoveUp)).assertIsNotEnabled()
        rule.onNodeWithTag(SheetTags.action(ThreadAction.MoveDown)).assertIsNotEnabled()
    }

    @Test fun partialSwipeRevealsActionsWithoutCommittingThenButtonRunsOnce() {
        val actions = mutableListOf<SwipeAction>()
        val swiping = row.copy(swipe = SwipeActions(SwipeAction.Settle, SwipeAction.Snooze, true))
        rule.setContent { Ot3Theme { ThreadSwipeActions(swiping, { actions += it }, Modifier.fillMaxWidth().testTag("swipe")) { modifier ->
            Box(modifier.height(82.dp)) { Text("Thread") }
        } } }
        rule.onNodeWithTag("swipe").performTouchInput { swipe(Offset(width * .9f, centerY), Offset(width * .6f, centerY), 300) }
        rule.waitForIdle()
        assertTrue(actions.isEmpty())
        rule.onNodeWithTag("thread_swipe_e1/t1_Snooze").assertIsDisplayed().performClick()
        assertEquals(listOf(SwipeAction.Snooze), actions)
    }

    @Test fun fullSwipeCommitsPrimaryOnceAndMirrorsInRtl() {
        val actions = mutableListOf<SwipeAction>()
        val swiping = row.copy(swipe = SwipeActions(SwipeAction.Wake, null, true))
        rule.setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Ot3Theme {
            ThreadSwipeActions(swiping, { actions += it }, Modifier.fillMaxWidth().testTag("swipe")) { modifier -> Box(modifier.height(82.dp)) { Text("Thread") } }
        } } }
        rule.onNodeWithTag("swipe").performTouchInput { swipe(Offset(width * .1f, centerY), Offset(width * .9f, centerY), 300) }
        rule.waitForIdle()
        assertEquals(listOf(SwipeAction.Wake), actions)
    }

    @Test fun disabledSwipeKeepsLongPressAndNeverCommits() {
        val actions = mutableListOf<SwipeAction>()
        var menus = 0
        rule.setContent { Ot3Theme {
            ThreadSwipeActions(row, { actions += it }, Modifier.fillMaxWidth().testTag("swipe")) { modifier ->
                DesktopThreadRow(row.toThreadRowModel(), onClick = {}, onLongClick = { menus++ }, modifier = modifier)
            }
        } }
        rule.onNodeWithTag("swipe").performTouchInput { longClick() }
        assertEquals(1, menus)
        rule.onNodeWithTag("swipe").performTouchInput { swipeLeft() }
        assertTrue(actions.isEmpty())
    }

    @Test fun destructiveActionWaitsForConfirmation() {
        var confirmed = 0
        rule.setContent { Ot3Theme { ThreadManagementDialog(row, ThreadAction.Delete, {}, { confirmed++ }) } }
        assertEquals(0, confirmed)
        rule.onNodeWithTag("thread_action_confirm").performClick()
        assertEquals(1, confirmed)
    }
}
