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
