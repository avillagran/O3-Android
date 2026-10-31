package dev.avillagran.ot3android.ui.pullrequests

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real navigation/JNI, a synthetic three-page server, exact retained visible index/pixel offset. */
class PullRequestsReturnDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun laterPageAndPixelAnchorSurviveDetailBackRecreateAndRotation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("dark", true).putExtra("request", "pr-retain")).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("nav_tab_PullRequests").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("pr_entry_100").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("pr_list").performScrollToNode(hasTestTag("pr_more"))
            rule.onNodeWithTag("pr_more").assertIsEnabled().performClick()
            waitForReads(scenario, 2)
            // Page 2's first row becomes the actual first visible item, clipped by 35 px.
            scenario.onActivity { activity -> activity.fixture.viewModelScope.launch { activity.fixture.prListState!!.scrollToItem(14, 35) } }
            waitForPosition(scenario, 14 to 35)
            capture("before-detail")
            rule.onNodeWithTag("pr_entry_200").assertIsDisplayed().performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("#200").fetchSemanticsNodes().isNotEmpty() }
            capture("detail")
            rule.onNodeWithText("Back").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("pr_entry_200").fetchSemanticsNodes().isNotEmpty() }
            waitForPosition(scenario, 14 to 35)
            assertReads(scenario, 2)
            capture("after-back")
            scenario.recreate()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("pr_entry_200").fetchSemanticsNodes().isNotEmpty() }
            waitForPosition(scenario, 14 to 35)
            assertReads(scenario, 2)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitUntil(15_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            rule.waitUntil(15_000) { rule.onNodeWithTag("pr_list").fetchSemanticsNode().boundsInRoot.let { it.width > it.height } }
            waitForPosition(scenario, 14 to 35)
            rule.onNodeWithTag("pr_entry_200").assertIsDisplayed()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            rule.waitUntil(15_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            rule.waitUntil(15_000) { rule.onNodeWithTag("pr_list").fetchSemanticsNode().boundsInRoot.let { it.height > it.width } }
            waitForPosition(scenario, 14 to 35)
            rule.onNodeWithTag("pr_entry_200").assertIsDisplayed()
            assertReads(scenario, 2)
            // Continue from the retained opaque page-three cursor, without reloading pages 1/2.
            rule.onNodeWithTag("pr_list").performScrollToNode(hasTestTag("pr_more"))
            rule.onNodeWithTag("pr_more").assertIsEnabled().performClick()
            waitForReads(scenario, 3)
            rule.onNodeWithTag("pr_list").performScrollToNode(hasTestTag("pr_entry_300"))
            rule.onNodeWithTag("pr_entry_300").assertIsDisplayed()
            scenario.onActivity { activity ->
                val requests = activity.fixture.transport.calls.filter { it.fn == "pullRequests.list" }
                assertFalse(requests[0].args.has("cursors"))
                assertEquals("fixture-page-2", requests[1].args.getJSONObject("cursors").getString("project"))
                assertEquals("fixture-page-3", requests[2].args.getJSONObject("cursors").getString("project"))
            }
        }
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("prReturnEvidence") != "true") return
        rule.waitForIdle()
        Thread.sleep(600) // Opt-in only: wait for SurfaceFlinger to present the verified frame.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "pr-return-$name.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun waitForPosition(scenario: ActivityScenario<WorkspaceFixtureActivity>, expected: Pair<Int, Int>) {
        var actual: Pair<Int, Int>? = null
        rule.waitUntil(15_000) {
            scenario.onActivity { activity -> activity.fixture.prListState?.let { actual = it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset } }
            actual == expected
        }
        assertEquals(expected, actual)
    }
    private fun waitForReads(scenario: ActivityScenario<WorkspaceFixtureActivity>, expected: Int) {
        var actual = 0
        var rows = 0
        rule.waitUntil(15_000) {
            scenario.onActivity {
                actual = it.fixture.transport.calls.count { call -> call.fn == "pullRequests.list" }
                rows = it.fixture.prListState?.layoutInfo?.totalItemsCount ?: 0
            }
            actual == expected && rows >= expected * 12 + 2 && rule.onAllNodesWithTag("pr_loading").fetchSemanticsNodes().isEmpty()
        }
    }
    private fun assertReads(scenario: ActivityScenario<WorkspaceFixtureActivity>, expected: Int) {
        scenario.onActivity { assertEquals(expected, it.fixture.transport.calls.count { call -> call.fn == "pullRequests.list" }) }
    }
}
