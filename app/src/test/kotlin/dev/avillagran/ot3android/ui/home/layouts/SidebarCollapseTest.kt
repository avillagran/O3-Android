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

    @Test @Config(qualifiers = "w320dp-h900dp-port-xhdpi")
    fun addProjectRemainsReachableOnNarrowScreenWithLargeText() {
        var intent: dev.avillagran.ot3android.ui.home.HomeIntent? = null
        val state = HomeUiState(content = HomeContent.List, header = HomeHeader(counts = CountPills(12, 173, 4)))
        rule.setContent { androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                androidx.compose.ui.platform.LocalDensity.current.density, 1.6f)) {
            Ot3Theme { HomeScreen(state, true, { intent = it }) }
        } }
        rule.onNodeWithTag("home_add_project").assertIsDisplayed().performClick()
        assertEquals(dev.avillagran.ot3android.ui.home.HomeIntent.AddProject, intent)
    }

    private fun closedShelfInLayout(layout: String) {
        val active = fixtureRow("node", "active")
        val closed = fixtureRow("node", "closed", settled = true)
        val filtered = fixtureRow("node", "filtered")
        val expanded = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            val state = HomeUiState(content = HomeContent.List,
                shelves = ShelfState(settledExpanded = expanded.value),
                rows = listOf(active, closed, filtered).associateBy { it.key },
                items = buildList {
                    add(HomeItem.Row(active))
                    add(HomeItem.ShelfHeader(Shelf.Settled, 1, expanded.value))
                    if (expanded.value) add(HomeItem.Row(closed))
                })
            Ot3Theme { HomeScreen(state, true, { intent ->
                if (intent is dev.avillagran.ot3android.ui.home.HomeIntent.ToggleShelf && intent.shelf == Shelf.Settled)
                    expanded.value = !expanded.value
            }, appearance = AppearanceSettings(local = mapOf(LocalAppearancePreference.INTERFACE_LAYOUT to layout))) }
        }
        rule.onNodeWithTag("home_row_${closed.key}").assertExists()
        rule.onNodeWithTag("home_row_${filtered.key}").assertDoesNotExist()
        rule.onNodeWithTag(dev.avillagran.ot3android.ui.home.HomeTags.shelf(Shelf.Settled)).performClick()
        rule.onNodeWithTag("home_row_${closed.key}").assertDoesNotExist()
        rule.onNodeWithTag("home_row_${active.key}").assertExists()
        rule.onNodeWithTag(dev.avillagran.ot3android.ui.home.HomeTags.shelf(Shelf.Settled)).performClick()
        rule.onNodeWithTag("home_row_${closed.key}").assertExists()
    }
    @Test fun classicClosedShelf() = closedShelfInLayout("classic")
    @Test fun focusClosedShelf() = closedShelfInLayout("focus")
    @Test fun projectsClosedShelf() = closedShelfInLayout("projects")

    private fun chipInLayout(layout: String) {
        val parent = fixtureRow("node", "parent-$layout")
        val child = fixtureRow("node", "child-$layout").copy(parentKey = parent.key, subagent = true)
        val rows = listOf(parent, child)
        val state = HomeUiState(content = HomeContent.List, rows = rows.associateBy { it.key }, items = rows.map { HomeItem.Row(it) })
        rule.setContent { Ot3Theme { HomeScreen(state, true, {}, appearance = AppearanceSettings(local = mapOf(LocalAppearancePreference.INTERFACE_LAYOUT to layout))) } }
        rule.onNodeWithContentDescription("Hide 1 subagents")
            .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            .assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            .assert(hasAnyAncestor(hasTestTag("home_row_${parent.key}"))).performClick()
        rule.waitUntil { rule.onAllNodesWithTag("home_row_${child.key}").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithContentDescription("Show 1 subagents").performClick()
        rule.waitUntil { rule.onAllNodesWithTag("home_row_${child.key}").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun classicChip() = chipInLayout("classic")
    @Test fun focusChip() = chipInLayout("focus")
    @Test fun projectsChip() = chipInLayout("projects")
}
