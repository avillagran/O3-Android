package dev.avillagran.ot3android.ui.thread

import dev.avillagran.ot3android.testing.ScreenshotTest
import org.json.JSONObject
import org.junit.Test
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*

class ReaderScreenshotTest : ScreenshotTest() {
    private val originalZone = java.util.TimeZone.getDefault()
    private val originalLocale = java.util.Locale.getDefault()
    @org.junit.Before fun stableClockLocale() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Santiago"))
        java.util.Locale.setDefault(java.util.Locale.US)
    }
    @org.junit.After fun restoreClockLocale() {
        java.util.TimeZone.setDefault(originalZone)
        java.util.Locale.setDefault(originalLocale)
    }
    @Test fun compactPromptAndOutsideActions() {
        val short = JSONObject().put("id", "short").put("role", "user").put("text", "Yes").put("createdAt", 1000)
        val long = JSONObject().put("id", "long").put("role", "user").put("text", "Keep the message readable while the longer prompt wraps inside the same right-aligned lane.").put("createdAt", 1000)
        snapshot("prompts") {
            androidx.compose.foundation.layout.Column {
                UserMessage(short)
                UserMessage(long)
            }
        }
        val bubble = composeRule.onNodeWithTag("thread_bubble_short").fetchSemanticsNode().boundsInRoot
        val lane = composeRule.onNodeWithTag(ThreadScreenTags.message("short")).fetchSemanticsNode().boundsInRoot
        val actions = composeRule.onNodeWithTag("thread_copy_message_short").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Short prompts must not reserve 80% width", bubble.width < lane.width / 2)
        org.junit.Assert.assertEquals(lane.right, bubble.right, 1f)
        org.junit.Assert.assertTrue("Copy belongs outside the bubble", actions.top >= bubble.bottom)
        val longBubble = composeRule.onNodeWithTag("thread_bubble_long").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(longBubble.width <= lane.width * .8f + 1)
    }

    @Test fun tableReader() {
        val blocks = org.json.JSONArray(checkNotNull(javaClass.getResource("/timeline/table.blocks.json")).readText())
        snapshot("table") {
            androidx.compose.foundation.layout.Column {
                MarkdownBlocks(blocks)
            }
        }
        composeRule.onNodeWithTag("thread_copy_table_csv_0").performClick()
        val clipboard = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        org.junit.Assert.assertEquals(blocks.getJSONObject(0).getString("csv"), clipboard.primaryClip!!.getItemAt(0).text.toString())
        composeRule.onNodeWithTag("thread_copy_table_markdown_0").performClick()
        org.junit.Assert.assertEquals(blocks.getJSONObject(0).getString("markdown"), clipboard.primaryClip!!.getItemAt(0).text.toString())
        composeRule.onNodeWithTag("thread_table_0").performTouchInput { swipeLeft() }
        composeRule.onNodeWithText("108").assertIsDisplayed()
    }

    @Test fun markdownReader() {
        val view = JSONObject(checkNotNull(javaClass.getResource("/timeline/golden.view.json")).readText())
        snapshot("markdown") { TimelineReader(view, emptyList()) }
    }
}
