package dev.avillagran.ot3android.ui.usage

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real navigation/VM/connection and JNI, synthetic diagnostics server. */
class UsageDeviceTest {
    private val recordMotionEvidence: Boolean
        get() = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("motionEvidence") == "true"
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun repeatedTabEntrancesKeepOneSubscription() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("dark", true)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("nav_tab_Settings").performClick()
            rule.onNodeWithTag("settings_Usage").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("usage_total_tokens").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("usage_metric_tokens").performClick()
            repeat(3) {
                if (recordMotionEvidence) Thread.sleep(1_000) // Opt-in evidence only.
                rule.mainClock.autoAdvance = !recordMotionEvidence
                rule.onNodeWithTag("usage_tab_limits").performClick()
                recordEntranceFrames()
                rule.waitUntil(15_000) { rule.onAllNodesWithText("38% used").fetchSemanticsNodes().isNotEmpty() }
                scenario.onActivity { assertEquals(1, it.fixture.transport.serverConfigCollectors().value) }
                if (recordMotionEvidence) Thread.sleep(1_000)
                rule.mainClock.autoAdvance = !recordMotionEvidence
                rule.onNodeWithTag("usage_tab_usage").performClick()
                recordEntranceFrames()
                rule.onNodeWithText("Tokens per day").assertIsDisplayed()
                scenario.onActivity { assertEquals(0, it.fixture.transport.serverConfigCollectors().value) }
            }
            if (recordMotionEvidence) Thread.sleep(1_000)
            scenario.onActivity {
                assertEquals(1, it.fixture.transport.calls.count { call -> call.fn == "server.refreshProviders" })
                assertTrue(it.fixture.transport.calls.none { call -> call.fn == "server.refreshUsageRates" })
            }
        }
    }

    private fun recordEntranceFrames() {
        if (!recordMotionEvidence) return
        // Compose tests otherwise fast-forward animations before screenrecord sees them.
        repeat(14) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            Thread.sleep(16)
        }
        rule.mainClock.autoAdvance = true
    }

    @Test fun limitsStreamRotationTabsAndBackReleaseSubscription() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("nav_tab_Settings").performClick()
            rule.onNodeWithTag("settings_Usage").performClick()
            rule.onNodeWithTag("usage_tab_limits").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("38% used").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("38% used").assertIsDisplayed()
            rule.onAllNodes(hasText("Resets ", substring = true)).onFirst().assertExists()
            rule.onAllNodes(hasText("Checked ", substring = true)).onFirst().assertExists()
            scenario.onActivity { assertEquals(1, it.fixture.transport.serverConfigCollectors().value); it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitForIdle()
            rule.onNodeWithTag("usage_tab_limits").assertIsSelected()
            rule.onNodeWithTag("usage_page").performScrollToNode(hasText("Team accounts"))
            rule.onNodeWithText("Team accounts").assertIsDisplayed()
            rule.onNodeWithTag("usage_page").performScrollToIndex(0)
            rule.onNodeWithTag("usage_tab_usage").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("usage_total_tokens").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { assertEquals(0, it.fixture.transport.serverConfigCollectors().value) }
            rule.onNodeWithTag("usage_tab_limits").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("38% used").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Back").performClick()
            rule.waitForIdle()
            scenario.onActivity {
                assertEquals(0, it.fixture.transport.serverConfigCollectors().value)
                assertEquals(1, it.fixture.transport.calls.count { call -> call.fn == "server.refreshProviders" })
            }
        }
    }

    @Test fun refreshPricesReadsUpdatedSummaryWithoutDispatch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("request", "prices")).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("nav_tab_Settings").performClick()
            rule.onNodeWithTag("settings_Usage").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("usage_total_tokens").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("usage_page").performScrollToNode(hasTestTag("usage_refresh_prices"))
            rule.onNodeWithTag("usage_refresh_prices").performClick()
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("usage_refresh_prices").fetchSemanticsNodes().isEmpty() &&
                    rule.onAllNodesWithText("gpt-6.1-sol").fetchSemanticsNodes().isNotEmpty()
            }
            scenario.onActivity {
                val calls = it.fixture.transport.calls.map { call -> call.fn }
                assertEquals(1, calls.count { name -> name == "server.refreshUsageRates" })
                assertEquals(2, calls.count { name -> name == "server.getUsageSummary" })
                assertFalse(calls.contains("orchestration.dispatchCommand"))
            }
        }
    }

    @Test fun settingsPeriodRotationRefreshAndBack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("nav_tab_Settings").performClick()
            rule.onNodeWithTag("settings_Usage").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("usage_total_tokens").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("usage_total_tokens").assertTextEquals("280,800")
            rule.onNodeWithTag("usage_period_day").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("51,600").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitForIdle()
            rule.onNodeWithTag("usage_period_day").assertIsSelected()
            rule.onNodeWithTag("usage_refresh").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("51,600").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { activity ->
                val reads = activity.fixture.transport.calls.filter { it.fn == "server.getUsageSummary" }
                assertTrue(reads.size >= 3)
                assertEquals("2026-10-26", reads.last().args.getString("sinceDay"))
                assertTrue(activity.fixture.transport.calls.none { it.fn == "orchestration.dispatchCommand" })
            }
            rule.onNodeWithTag("usage_page").performScrollToNode(hasText("Models"))
            rule.onNodeWithText("Models").assertIsDisplayed()
            rule.onNodeWithText("Back").performClick()
            rule.onNodeWithTag("settings_Usage").assertIsDisplayed()
        }
    }
}
