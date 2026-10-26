package dev.avillagran.ot3android.ui.usage

import androidx.compose.ui.test.*
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.testing.ScreenshotTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class UsageScreenshotTest : ScreenshotTest() {
    private lateinit var previousTimeZone: TimeZone

    @Before fun useGoldenTimeZone() {
        previousTimeZone = TimeZone.getDefault()
        // Reset/check times are intentionally device-local. Fix only the screenshot
        // environment so UTC CI and Santiago development render the same fixture.
        TimeZone.setDefault(TimeZone.getTimeZone("America/Santiago"))
    }

    @After fun restoreTimeZone() {
        TimeZone.setDefault(previousTimeZone)
    }

    @Test fun nativeProviderAndHubWindows() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val nativeState = JSONObject(context.assets.open("usage/limits.view.json").bufferedReader().use { it.readText() })
        val state = UsageState(listOf(Environment("node", "NODE", "http://node.test", createdAt = 0)), "node", tab = "limits", limits = nativeState)
        snapshot("limits") { UsageScreen(state, {}, {}, {}, {}) }
        composeRule.onNodeWithText("38% used").assertIsDisplayed()
        composeRule.onNodeWithText("Team accounts").assertExists()
    }

    @Test fun unavailablePricesExposeExplicitAction() {
        val summary = JSONObject(checkNotNull(javaClass.getResource("/usage/summary.view.json")).readText())
            .put("pricing", "unavailable").put("unpricedRecords", 3)
        var requests = 0
        val state = UsageState(listOf(Environment("node", "NODE", "http://node.test", createdAt = 0)), "node", summary = summary)
        snapshot("unpriced") { UsageScreen(state, {}, {}, {}, {}, onRefreshPrices = { requests++ }) }
        composeRule.onNodeWithTag("usage_page").performScrollToNode(hasTestTag("usage_refresh_prices"))
        composeRule.onNodeWithTag("usage_refresh_prices").performClick()
        assertEquals(1, requests)
        composeRule.onNodeWithText("3 records have no price.").assertExists()
    }

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
