package dev.avillagran.ot3android.ui.usage

import androidx.compose.ui.test.*
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.testing.ScreenshotTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageScreenshotTest : ScreenshotTest() {
    @Test fun summaryAndControls() {
        val summary = JSONObject(checkNotNull(javaClass.getResource("/usage/summary.view.json")).readText())
        var period = ""
        var environment = ""
        var refreshes = 0
        val state = UsageState(listOf(Environment("node", "NODE", "http://node.test", createdAt = 0), Environment("vq", "VQ", "http://vq.test", createdAt = 0)), "node", summary = summary)
        snapshot("summary") { UsageScreen(state, { environment = it }, { period = it }, { refreshes++ }, {}) }
        composeRule.onNodeWithTag("usage_total_tokens").assertIsDisplayed()
        composeRule.onNodeWithTag("usage_period_day").performClick()
        assertEquals("day", period)
        composeRule.onNodeWithTag("usage_refresh").performClick()
        assertEquals(1, refreshes)
        composeRule.onNodeWithTag("usage_environment").performClick()
        composeRule.onNodeWithText("VQ").performClick()
        assertEquals("vq", environment)
        composeRule.onNodeWithTag("usage_metric_tokens").performClick()
        composeRule.onNodeWithText("Tokens per day").assertIsDisplayed()
        composeRule.onNodeWithTag("usage_day_6").performClick()
        composeRule.onNodeWithText("2026-10-26 · 51,600").assertIsDisplayed()
    }
}
