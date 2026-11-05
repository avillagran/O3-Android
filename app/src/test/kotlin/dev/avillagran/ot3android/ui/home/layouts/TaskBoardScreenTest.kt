package dev.avillagran.ot3android.ui.home.layouts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.data.prefs.*
import dev.avillagran.ot3android.core.home.*
import dev.avillagran.ot3android.ui.home.*
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class TaskBoardScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val appearance = AppearanceSettings(local = mapOf(LocalAppearancePreference.INTERFACE_LAYOUT to "board"))

    @Test fun boardShowsClosedThreadsDespiteListFoldAndPreservesThreadActions() {
        val pending = fixtureRow("node", "pending")
        val closed = fixtureRow("node", "closed", settled = true)
        val filtered = fixtureRow("node", "filtered")
        val state = HomeUiState(content = HomeContent.List, boardRows = listOf(pending, closed),
            rows = listOf(pending, closed, filtered).associateBy { it.key },
            items = listOf(HomeItem.Row(pending), HomeItem.ShelfHeader(Shelf.Settled, 1, false)))
        var intent: HomeIntent? = null
        rule.setContent { Ot3Theme { HomeScreen(state, true, { intent = it }, appearance = appearance) } }
        rule.onNodeWithTag("home_row_${pending.key}").assertIsDisplayed()
        rule.onNodeWithTag("home_row_${filtered.key}").assertDoesNotExist()
        rule.onNodeWithTag("task_board_columns").performScrollToNode(hasTestTag("task_board_Closed"))
        rule.onNodeWithTag("home_row_${closed.key}").assertIsDisplayed().performClick()
        assertEquals(HomeIntent.OpenRow(closed), intent)
        rule.onNodeWithTag("home_row_${closed.key}").performTouchInput { longClick(center.copy(y = height * .2f)) }
        assertEquals(HomeIntent.RowActions(closed), intent)
        rule.onNodeWithTag("task_board_toggle_Closed").performClick()
        rule.waitUntil { rule.onAllNodesWithTag("home_row_${closed.key}").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun horizontalCardGestureNavigatesColumnsWithoutMutatingThreadState() {
        val pending = fixtureRow("node", "pending").copy(swipe = SwipeActions(SwipeAction.Settle, null, true))
        val working = fixtureRow("node", "working", "working")
        val state = HomeUiState(content = HomeContent.List, boardRows = listOf(pending, working))
        val intents = mutableListOf<HomeIntent>()
        rule.setContent { Ot3Theme { HomeScreen(state, true, { intents.add(it) }, appearance = appearance) } }
        rule.onNodeWithTag("home_row_${pending.key}").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("home_row_${working.key}").assertIsDisplayed()
        assertTrue(intents.none { it is HomeIntent.Swipe })
    }

    @Test fun boardModePersistsAndQuickToggleReturnsToPreviousListLayout() {
        val preferences = AppearancePreferences(rule.activity)
        val initial = AppearanceSettings(local = mapOf(LocalAppearancePreference.INTERFACE_LAYOUT to "projects"))
        runBlocking { preferences.setLocal(LocalAppearancePreference.INTERFACE_LAYOUT, "projects") }
        val row = fixtureRow("node", "one")
        val state = HomeUiState(content = HomeContent.List, boardRows = listOf(row), items = listOf(HomeItem.Row(row)))
        rule.setContent {
            val settings by preferences.settings.collectAsState(initial)
            Ot3Theme { HomeScreen(state, true, {}, appearance = settings) }
        }
        rule.onNodeWithTag("home_toggle_board").performClick()
        rule.waitUntil { rule.onAllNodesWithTag("home_task_board").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("board", runBlocking { preferences.settings.first().interfaceLayout })
        rule.onNodeWithTag("home_toggle_board").performClick()
        rule.waitUntil { rule.onAllNodesWithTag("home_task_board").fetchSemanticsNodes().isEmpty() }
        assertEquals("projects", runBlocking { preferences.settings.first().interfaceLayout })
    }
    @Test @Config(qualifiers = "w320dp-h900dp-port-xhdpi")
    fun emptyBoardRemainsAvailableOnSmallScreens() {
        rule.setContent { Ot3Theme { HomeScreen(HomeUiState(content = HomeContent.Empty, boardRows = emptyList()), true, {}, appearance = appearance) } }
        rule.onNodeWithTag("home_task_board").assertIsDisplayed()
        rule.onNodeWithTag("task_board_columns").performScrollToNode(hasTestTag("task_board_Done"))
        rule.onNodeWithTag("task_board_toggle_Done").assertIsDisplayed()
    }
}
