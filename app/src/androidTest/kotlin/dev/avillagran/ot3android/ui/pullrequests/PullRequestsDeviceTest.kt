package dev.avillagran.ot3android.ui.pullrequests

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real navigation, VM, JNI request/Markdown; synthetic read-only server. No production network. */
class PullRequestsDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun tokyoReader() = read(true)
    @Test fun latteReader() = read(false)
    @Test fun homeBadgeOpensNativeSelectedReference() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("Pull requests 42, Draft", useUnmergedTree = true).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Read-only review").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("example/fixture").assertIsDisplayed()
            scenario.onActivity { activity ->
                val request = activity.fixture.transport.calls.first { it.fn == "pullRequests.detail" }
                assertEquals("example/fixture", request.args.getString("repository"))
                assertEquals("github.com", request.args.getString("host"))
                assertEquals(42, request.args.getInt("number"))
            }
        }
    }
    private fun read(dark: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java).putExtra("dark", dark)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("nav_tab_PullRequests").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("pr_entry_42").fetchSemanticsNodes().isNotEmpty() }
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("pr_loading").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("pr_entry_42").assertIsDisplayed()
            capture(dark, "list")
            rule.onNodeWithTag("pr_project").performClick()
            rule.onNodeWithText("ot3-Android").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("pr_entry_42").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("pr_entry_42").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Read-only review").fetchSemanticsNodes().isNotEmpty() }
            capture(dark, "overview")
            rule.onNodeWithTag("pr_tab_checks").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Android build").fetchSemanticsNodes().isNotEmpty() }
            capture(dark, "checks")
            rule.onNodeWithTag("pr_tab_activity").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("ui/PullRequestsScreen.kt").fetchSemanticsNodes().isNotEmpty() }
            capture(dark, "conversation")
            scenario.recreate()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("ui/PullRequestsScreen.kt").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("pr_tab_activity").assertIsSelected()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitUntil(15_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            rule.onNodeWithTag("pr_tab_activity").assertIsSelected()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            rule.waitUntil(15_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            rule.onNodeWithTag("pr_tab_activity").assertIsSelected()
            rule.onNodeWithText("Back").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("pr_entry_42").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("pr_project").assertTextContains("ot3-Android")
            rule.onNodeWithTag("pr_list").performScrollToNode(hasTestTag("pr_more"))
            rule.onNodeWithTag("pr_more").assertIsEnabled().performClick()
            var cursorRead = false
            rule.waitUntil(15_000) {
                scenario.onActivity { activity -> cursorRead = activity.fixture.transport.calls.any { it.fn == "pullRequests.list" && it.args.has("cursors") } }
                cursorRead
            }
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("pr_more").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("pr_list").performScrollToNode(hasTestTag("pr_entry_36"))
            rule.onNodeWithTag("pr_entry_36").assertIsDisplayed()
            scenario.onActivity { activity ->
                val reads = activity.fixture.transport.calls.filter { it.fn.startsWith("pullRequests.") }
                assertTrue(reads.any { it.fn == "pullRequests.list" && it.args.optString("projectId") == "project" })
                assertTrue(reads.any { it.fn == "pullRequests.list" && it.args.has("cursors") })
                assertTrue(reads.all { it.fn in listOf("pullRequests.list", "pullRequests.detail", "pullRequests.checks", "pullRequests.activity") })
                assertTrue(activity.fixture.transport.calls.none { it.fn == "orchestration.dispatchCommand" })
            }
        }
    }
    private fun capture(dark: Boolean, page: String) {
        if (InstrumentationRegistry.getArguments().getString("prEvidence") != "true") return
        rule.waitForIdle()
        Thread.sleep(800) // Opt-in only: let SurfaceFlinger present the observed Compose frame.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "pr-${if (dark) "tokyo" else "latte"}-$page.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
