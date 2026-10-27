package dev.avillagran.ot3android.ui.thread

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic transport; actual Home/thread graph, typed diff read and Rust035 parsing. */
class DiffHunkFoldDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun tokyoHunksSurviveRotation() = exercise(true)
    @Test fun latteHunksSurviveRotation() = exercise(false)

    @Test fun tokyoWideSplitKeepsIndependentFoldsAcrossPhoneRotation() = exerciseSplit(true)
    @Test fun latteWideSplitKeepsIndependentFoldsAcrossPhoneRotation() = exerciseSplit(false)

    private fun exerciseSplit(dark: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("rich", true).putExtra("dark", dark)).use { scenario ->
            rotate(scenario, false)
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_fold_changes/cp-reader"))
            rule.onNodeWithTag("thread_fold_changes/cp-reader").performClick()
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_diff_changes/cp-reader"))
            rule.onNodeWithTag("thread_diff_changes/cp-reader").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("thread_diff_hunk_0_2").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("thread_diff_layout").assertDoesNotExist()
            val name = if (dark) "tokyo" else "latte"
            capture("$name-narrow-unified")
            rotate(scenario, true)
            val density = context.resources.displayMetrics.density
            assertTrue("Actual content width must support split", rule.onNodeWithTag("thread_diff_lines").fetchSemanticsNode().boundsInRoot.width / density >= 650)
            var readsBeforeLayout = 0
            scenario.onActivity { readsBeforeLayout = it.fixture.transport.calls.count { call -> call.fn == "orchestration.getTurnDiff" } }
            rule.onNodeWithTag("thread_diff_layout").assertTextContains("Split").performClick()
            rule.onNodeWithTag("thread_diff_layout").assertTextContains("Unified")
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertIsDisplayed()
            capture("$name-wide-split")
            val header = rule.onNodeWithTag("thread_diff_hunk_0_2")
            header.performTouchInput { click(center) }
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertDoesNotExist()
            capture("$name-wide-split-folded")
            rule.onNodeWithTag("thread_diff_layout").performClick()
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertIsDisplayed()
            rule.onNodeWithTag("thread_diff_layout").performClick()
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertDoesNotExist()
            scenario.onActivity { assertEquals(readsBeforeLayout, it.fixture.transport.calls.count { call -> call.fn == "orchestration.getTurnDiff" }) }
            rotate(scenario, false)
            rule.onNodeWithTag("thread_diff_layout").assertDoesNotExist()
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertIsDisplayed()
            rotate(scenario, true)
            rule.onNodeWithTag("thread_diff_layout").assertTextContains("Unified")
            header.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertDoesNotExist()
            scenario.onActivity { readsBeforeLayout = it.fixture.transport.calls.count { call -> call.fn == "orchestration.getTurnDiff" } }
            header.performTouchInput { click(center) }
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertIsDisplayed()
            rule.onNodeWithTag("thread_diff_wrap").performClick()
            rule.onNodeWithTag("thread_diff_horizontal").assertExists()
            rule.onNodeWithTag("thread_diff_next").performClick()
            val horizontal = rule.onNodeWithTag("thread_diff_horizontal")
            assertTrue(horizontal.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0)
            horizontal.performTouchInput { swipeLeft() }
            assertTrue(horizontal.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange].value() > 0)
            rule.onNodeWithTag("thread_diff_previous").performClick()
            rule.onNodeWithTag("thread_diff_layout").assertTextContains("Unified")
            scenario.onActivity {
                assertEquals(readsBeforeLayout, it.fixture.transport.calls.count { call -> call.fn == "orchestration.getTurnDiff" })
                assertEquals(1, it.fixture.transport.streamOpens.count { stream -> stream.threadId == "chat" })
                assertTrue(it.fixture.transport.calls.none { call -> call.fn == "orchestration.dispatchCommand" })
            }
        }
    }

    private fun exercise(dark: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("rich", true).putExtra("dark", dark)).use { scenario ->
            rotate(scenario, false)
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_fold_changes/cp-reader"))
            rule.onNodeWithTag("thread_fold_changes/cp-reader").performClick()
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_diff_changes/cp-reader"))
            rule.onNodeWithTag("thread_diff_changes/cp-reader").performScrollTo().assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("thread_diff_hunk_0_2").fetchSemanticsNodes().isNotEmpty() }
            val name = if (dark) "tokyo" else "latte"
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertIsDisplayed()
            rule.onNodeWithText("+KeepFullPatchForCopy()").assertIsDisplayed()
            capture("$name-expanded")
            val header = rule.onNodeWithTag("thread_diff_hunk_0_2")
            header.performTouchInput { click(center) }
            header.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertDoesNotExist()
            rule.onNodeWithText("+KeepFullPatchForCopy()").assertIsDisplayed()
            capture("$name-folded")
            scenario.onActivity { assertEquals(1, it.fixture.transport.calls.count { call -> call.fn == "orchestration.getTurnDiff" }) }
            rotate(scenario, true)
            header.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertDoesNotExist()
            rule.onNodeWithTag("thread_diff_lines").performScrollToNode(hasText("+KeepFullPatchForCopy()"))
            rule.onNodeWithText("+KeepFullPatchForCopy()").assertIsDisplayed()
            rotate(scenario, false)
            rule.onNodeWithTag("thread_diff_lines").performScrollToNode(hasTestTag("thread_diff_hunk_0_2"))
            header.performTouchInput { click(center) }
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertIsDisplayed()
            header.performClick()
            rule.onNodeWithTag("thread_diff_next").performClick()
            rule.onNodeWithTag("thread_diff_previous").performClick()
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertIsDisplayed()
            header.performClick()
            rule.onNodeWithTag("thread_diff_whitespace").performClick()
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertIsDisplayed()
            rule.onNodeWithTag("thread_diff_close").performClick()
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).assertIsDisplayed()
            scenario.onActivity {
                assertEquals(1, it.fixture.transport.streamOpens.count { stream -> stream.threadId == "chat" })
                assertTrue(it.fixture.transport.calls.none { call -> call.fn == "orchestration.dispatchCommand" })
            }
        }
    }
    private fun rotate(scenario: ActivityScenario<WorkspaceFixtureActivity>, landscape: Boolean) {
        scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitUntil(10_000) {
            var ready = false
            scenario.onActivity { ready = it.resources.configuration.orientation ==
                (if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT) &&
                it.window.decorView.let { view -> if (landscape) view.width > view.height else view.height > view.width } }
            ready
        }
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("hunkEvidence") != "true") return
        rule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "diff-hunks/$name.png").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
