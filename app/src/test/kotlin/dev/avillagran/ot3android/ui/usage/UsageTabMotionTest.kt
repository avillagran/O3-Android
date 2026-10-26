package dev.avillagran.ot3android.ui.usage

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import org.json.JSONObject
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.theme.LocalOt3Motion
import dev.avillagran.ot3android.ui.theme.Ot3MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTabMotionTest : ScreenshotTest() {
    @Test fun switchingBodyKeepsMetricAndScopeWithoutRefresh() {
        val tab = mutableStateOf("usage")
        val summary = JSONObject(checkNotNull(javaClass.getResource("/usage/summary.view.json")).readText())
        var refreshes = 0
        composeRule.setContent {
            Ot3Theme {
            UsageScreen(UsageState(environmentId = "node", period = "month", tab = tab.value, summary = summary),
                {}, {}, { refreshes++ }, {}, onTab = { tab.value = it })
            }
        }
        composeRule.onNodeWithTag("usage_metric_tokens").performClick()
        composeRule.onNodeWithTag("usage_tab_limits").performClick()
        composeRule.onNodeWithTag("usage_total_tokens").assertDoesNotExist()
        composeRule.onNodeWithTag("usage_tab_usage").performClick()
        composeRule.onNodeWithText("Tokens per day").assertIsDisplayed()
        composeRule.onNodeWithTag("usage_period_month").assertIsSelected()
        assertEquals(0, refreshes)
    }

    @Test fun tabEntranceRestartsAndMotionOffSnaps() {
        val tab = mutableStateOf("usage")
        val motion = mutableStateOf(Ot3MotionPolicy.Full)
        var alpha = 1f
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalOt3Motion provides motion.value) {
                val opacity = rememberUsageTabOpacity(tab.value)
                val value = opacity.value
                SideEffect { alpha = value }
            }
        }
        composeRule.mainClock.advanceTimeBy(80)
        composeRule.runOnIdle { assertTrue(alpha > 0f && alpha < 1f) }
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.runOnIdle { assertEquals(1f, alpha) }
        composeRule.runOnIdle { tab.value = "limits" }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.runOnIdle { assertTrue(alpha < 1f) }
        composeRule.runOnIdle { motion.value = Ot3MotionPolicy.Off }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle { assertEquals(1f, alpha) }
        composeRule.runOnIdle { tab.value = "usage" }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle { assertEquals(1f, alpha) }
    }
}
