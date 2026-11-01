package dev.avillagran.ot3android.ui.home.layouts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.data.prefs.*
import dev.avillagran.ot3android.core.home.*
import dev.avillagran.ot3android.ui.home.HomeScreen
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
class SidebarCollapseTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun fifthRootRetainsItsChildrenWhenTheProjectLimitIsActive() {
        val roots = (1..6).map { fixtureRow("node", "root-$it").copy(title = "Root $it") }
        val child = fixtureRow("node", "last-visible-child").copy(parentKey = roots[4].key, subagent = true)
        val rows = roots + child
        val state = HomeUiState(content = HomeContent.List, shelves = ShelfState(grouped = true), rows = rows.associateBy { it.key }, items = rows.map { HomeItem.Row(it) })
        rule.setContent { Ot3Theme { HomeScreen(state, true, {}, appearance = AppearanceSettings(local = mapOf(LocalAppearancePreference.INTERFACE_LAYOUT to "projects"))) } }
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_row_${child.key}"))
        rule.onNodeWithTag("home_row_${child.key}").assertIsDisplayed()
        rule.onNodeWithTag("home_row_${roots[5].key}").assertDoesNotExist()
    }

    @Test fun projectAndNestedCollapsePersistIndependently() {
        val parent = fixtureRow("node", "parent")
        val child = fixtureRow("node", "child").copy(parentKey = parent.key, subagent = true)
        val rows = listOf(parent, child)
        val state = HomeUiState(content = HomeContent.List, rows = rows.associateBy { it.key }, items = rows.map { HomeItem.Row(it) })
        rule.setContent { Ot3Theme { HomeScreen(state, true, {}, appearance = AppearanceSettings(local = mapOf(LocalAppearancePreference.INTERFACE_LAYOUT to "projects"))) } }
        rule.onNodeWithTag("home_row_${child.key}").assertExists()
        rule.onNodeWithContentDescription("Hide 1 subagents").assert(hasAnyAncestor(hasTestTag("home_row_${parent.key}"))).performClick()
        rule.waitUntil { rule.onAllNodesWithTag("home_row_${child.key}").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("home_row_${parent.key}").assertExists()
        rule.onNodeWithTag("sidebar_toggle_project:node/app").performClick()
        rule.waitUntil { rule.onAllNodesWithTag("home_row_${parent.key}").fetchSemanticsNodes().isEmpty() }
        val saved = runBlocking { HomePreferences(rule.activity).sidebarCollapsed.first() }
        assertTrue("thread:${parent.key}" in saved)
        assertTrue("project:node/app" in saved)
        rule.onNodeWithTag("sidebar_toggle_project:node/app").performClick()
        rule.waitUntil { rule.onAllNodesWithTag("home_row_${parent.key}").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("home_row_${child.key}").assertDoesNotExist()
    }
}
