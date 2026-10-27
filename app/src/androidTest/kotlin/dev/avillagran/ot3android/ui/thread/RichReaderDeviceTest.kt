package dev.avillagran.ot3android.ui.thread

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real graph/VM/session/Rust view; only the server is deterministic and offline. */
class RichReaderDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun diffRemainsOpenWhenRotationMovesItsTimelineRowOffscreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("rich", true)).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            rule.waitUntil(15_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_fold_changes/cp-reader"))
            rule.onNodeWithTag("thread_fold_changes/cp-reader").performClick()
            // Expansion changes the lazy row's height; scroll the actual action into
            // view before tapping, rather than a clipped semantics-only descendant.
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_diff_changes/cp-reader"))
            rule.onNodeWithTag("thread_diff_changes/cp-reader").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("thread_diff_next").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("thread_diff_next").performClick()
            rule.onNodeWithTag("thread_diff_wrap").performClick()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            rule.onNodeWithTag("thread_diff_file_picker").assertTextContains("ui/ThreadWorkspace.kt")
            rule.onNodeWithTag("thread_diff_horizontal").assertExists()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            rule.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            rule.onNodeWithTag("thread_diff_file_picker").assertTextContains("ui/ThreadWorkspace.kt")
            rule.onNodeWithTag("thread_diff_horizontal").assertExists()
            rule.onNodeWithTag("thread_diff_close").performClick()
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).assertIsDisplayed()
            scenario.onActivity { activity ->
                assertEquals(1, activity.fixture.transport.streamOpens.count { it.threadId == "chat" })
                assertTrue(activity.fixture.transport.calls.none { it.fn == "orchestration.dispatchCommand" })
            }
        }
    }

    @Test fun markdownCodeScrollCopyAndScopedReasoningAndChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("rich", true)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("thread_code_3").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_copy_table_csv_5"))
            rule.onNodeWithTag("thread_copy_table_csv_5").performClick()
            scenario.onActivity {
                val clipboard = it.getSystemService(ClipboardManager::class.java)
                val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
                assertTrue(copied.startsWith("Host,Branch,State,Runs"))
                assertTrue(copied.contains("\"Ready, awaiting review\""))
            }
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_table_5"))
            val table = rule.onNodeWithTag("thread_table_5")
            assertTrue(table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0)
            table.performTouchInput { swipeLeft() }
            rule.waitForIdle()
            assertTrue(table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0)
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_copy_code_3"))
            rule.onNodeWithTag("thread_copy_code_3").performClick()
            scenario.onActivity {
                val clipboard = it.getSystemService(ClipboardManager::class.java)
                assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().startsWith("val thread = session.view.value"))
            }
            val code = rule.onNodeWithTag("thread_code_3")
            val range = code.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue("Long Rust code blocks must actually overflow", range.maxValue() > 0)
            code.performTouchInput { swipeLeft() }
            rule.waitForIdle()
            assertTrue("Horizontal swipe must move code", code.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0)
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_fold_work/think-reader"))
            rule.onNodeWithTag("thread_fold_work/think-reader").performClick()
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_inspect_work/think-reader_0"))
            rule.onNodeWithTag("thread_inspect_work/think-reader_0").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("thread_copy_inspector_reasoning").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Reasoning").assertExists()
            rule.onNodeWithText("Compare the native reader with the reference", substring = true).assertExists()
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_fold_changes/cp-reader"))
            rule.onNodeWithTag("thread_fold_changes/cp-reader").performClick()
            rule.onNodeWithText("ui/TimelineReader.kt", substring = true).assertExists()
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_diff_changes/cp-reader"))
            rule.onNodeWithTag("thread_diff_changes/cp-reader").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("+MarkdownBlocks(nativeBlocks)").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("-Text(markdown)").assertIsDisplayed()
            rule.onNodeWithTag("thread_diff_previous").assertIsNotEnabled()
            rule.onNodeWithTag("thread_diff_next").performClick()
            rule.onNodeWithText("+rememberSelectedFile(", substring = true).assertIsDisplayed()
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertDoesNotExist()
            rule.onNodeWithTag("thread_diff_next").assertIsNotEnabled()
            rule.onNodeWithTag("thread_diff_previous").performClick()
            rule.onNodeWithText("+MarkdownBlocks(nativeBlocks)").assertIsDisplayed()
            rule.onNodeWithTag("thread_diff_file_picker").performClick()
            rule.onNodeWithTag("thread_diff_file_1").performClick()
            rule.onNodeWithText("+rememberSelectedFile(", substring = true).assertIsDisplayed()
            rule.onNodeWithTag("thread_diff_wrap").performClick()
            rule.onNodeWithTag("thread_diff_horizontal").assertExists()
            val patch = rule.onNodeWithTag("thread_diff_horizontal")
            assertTrue(patch.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0)
            patch.performTouchInput { swipeLeft() }
            rule.waitForIdle()
            assertTrue(patch.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0)
            rule.onNodeWithTag("thread_diff_wrap").performClick()
            rule.onNodeWithTag("thread_diff_horizontal").assertDoesNotExist()
            rule.onNodeWithTag("thread_diff_whitespace").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("+rememberSelectedFile(", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("thread_diff_file_picker").assertTextContains("ui/ThreadWorkspace.kt")
            rule.onNodeWithTag("thread_diff_close").performClick()
            rule.onNodeWithText("ui/TimelineReader.kt", substring = true).assertExists()
            scenario.onActivity { activity ->
                val diffs = activity.fixture.transport.calls.filter { it.fn == "orchestration.getTurnDiff" }
                assertEquals(2, diffs.size)
                assertTrue(diffs.first().args.getBoolean("ignoreWhitespace"))
                assertFalse(diffs.last().args.getBoolean("ignoreWhitespace"))
                val reads = activity.fixture.transport.calls.filter { it.fn == "orchestration.getTurnItem" }
                assertEquals(1, reads.size)
                assertTrue("Reading tools must never dispatch a message", activity.fixture.transport.calls.none { it.fn == "orchestration.dispatchCommand" })
            }
        }
    }
}
