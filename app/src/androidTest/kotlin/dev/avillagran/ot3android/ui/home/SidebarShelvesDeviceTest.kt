package dev.avillagran.ot3android.ui.home

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.core.home.Shelf
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SidebarShelvesDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun emptyShortAndLongListsKeepIndependentBottomShelves() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("empty", "short", "long").forEach { size ->
            ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
                .putExtra("request", "sidebar-$size").putExtra("fontScale", if (size == "short") 1.6f else 1f)).use { scenario ->
                val snoozed = HomeTags.shelf(Shelf.Snoozed)
                val settled = HomeTags.shelf(Shelf.Settled)
                rule.waitUntil(15_000) { rule.onAllNodesWithTag(settled).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag(snoozed).assertIsDisplayed()
                rule.onNodeWithTag(settled).assertIsDisplayed()
                val before = rule.onNodeWithTag(settled).fetchSemanticsNode().boundsInRoot
                if (size == "long") rule.onNodeWithTag(HomeTags.LIST).performScrollToIndex(50)
                assertEquals(before, rule.onNodeWithTag(settled).fetchSemanticsNode().boundsInRoot)
                rule.onNodeWithTag(snoozed).performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithTag("home_shelf_list_Snoozed").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag(settled).performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithTag("home_shelf_list_Settled").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("home_shelf_list_Snoozed").performScrollToIndex(20)
                rule.onNodeWithTag(settled).assertIsDisplayed()
                rule.onNodeWithTag("home_shelf_list_Settled").assertIsDisplayed()
                val main = rule.onNodeWithTag(HomeTags.LIST).fetchSemanticsNode().boundsInRoot
                val shelf = rule.onNodeWithTag(snoozed).fetchSemanticsNode().boundsInRoot
                assertTrue(main.bottom <= shelf.top)
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                rule.waitForIdle()
                rule.onNodeWithTag(snoozed).assertIsDisplayed()
                rule.onNodeWithTag(settled).assertIsDisplayed()
                assertTrue(rule.onNodeWithTag(snoozed).fetchSemanticsNode().boundsInRoot.bottom <=
                    rule.onNodeWithTag(settled).fetchSemanticsNode().boundsInRoot.top)
            }
        }
    }
}
