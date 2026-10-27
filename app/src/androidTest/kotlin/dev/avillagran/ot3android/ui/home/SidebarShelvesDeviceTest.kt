package dev.avillagran.ot3android.ui.home

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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

    private fun awaitOrientation(scenario: ActivityScenario<WorkspaceFixtureActivity>, landscape: Boolean) {
        rule.waitUntil(15_000) {
            var windowReady = false
            scenario.onActivity {
                val decor = it.window.decorView
                windowReady = it.resources.configuration.orientation ==
                    (if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT) &&
                    decor.width > 0 && decor.height > 0 &&
                    (if (landscape) decor.width > decor.height else decor.height > decor.width)
            }
            windowReady && rule.onAllNodesWithTag(HomeTags.ROOT).fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.let {
                if (landscape) it.width > it.height else it.height > it.width
            } == true
        }
    }

    private fun assertHeaders(fontScale: Float, phase: String, scenario: ActivityScenario<WorkspaceFixtureActivity>) {
        val root = rule.onNodeWithTag(HomeTags.ROOT).fetchSemanticsNode().boundsInRoot
        val snoozed = HomeTags.shelf(Shelf.Snoozed)
        val settled = HomeTags.shelf(Shelf.Settled)
        rule.onNodeWithTag(snoozed).assertIsDisplayed()
        rule.onNodeWithTag(settled).assertIsDisplayed()
        val first = rule.onNodeWithTag(snoozed).fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag(settled).fetchSemanticsNode().boundsInRoot
        val main = rule.onNodeWithTag(HomeTags.LIST).fetchSemanticsNode().boundsInRoot
        assertTrue("$phase main overlaps shelves", main.bottom <= first.top)
        assertTrue("$phase shelf headers overlap", first.bottom <= second.top)
        listOf(first, second).forEach {
            assertTrue("$phase clipped shelf header", it.top >= root.top && it.bottom <= root.bottom && it.left >= root.left && it.right <= root.right)
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNode(hasAnyAncestor(hasTestTag(snoozed)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(fontScale, layouts.single().layoutInput.density.fontScale, 0f)
        scenario.onActivity {
            android.util.Log.i("SidebarShelvesTest", "$phase config=${it.resources.configuration.orientation} decor=${it.window.decorView.width}x${it.window.decorView.height} root=$root main=$main snoozed=$first settled=$second fontScale=$fontScale")
        }
    }

    @Test fun emptyShortAndLongListsKeepIndependentBottomShelves() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("empty", "short", "long").forEach { size ->
            ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
                .putExtra("request", "sidebar-$size").putExtra("fontScale", if (size == "short") 1.6f else 1f)).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                awaitOrientation(scenario, landscape = false)
                val fontScale = if (size == "short") 1.6f else 1f
                val snoozed = HomeTags.shelf(Shelf.Snoozed)
                val settled = HomeTags.shelf(Shelf.Settled)
                rule.waitUntil(15_000) { rule.onAllNodesWithTag(settled).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag(snoozed).assertIsDisplayed()
                rule.onNodeWithTag(settled).assertIsDisplayed()
                assertHeaders(fontScale, "$size-portrait", scenario)
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
                awaitOrientation(scenario, landscape = true)
                assertHeaders(fontScale, "$size-landscape", scenario)
                scenario.recreate()
                awaitOrientation(scenario, landscape = true)
                assertHeaders(fontScale, "$size-landscape-recreated", scenario)
            }
        }
    }
}
