package dev.avillagran.ot3android.ui.usage

import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.home.HomeScreen
import dev.avillagran.ot3android.core.home.*
import org.json.JSONObject
import org.junit.*
import java.util.TimeZone

/** Native sanitized fixture captured by the core pipeline; never loads JNI in Robolectric. */
class QuotaStripScreenshotTest : ScreenshotTest() {
    private lateinit var previousZone: TimeZone
    @Before fun zone() { previousZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Santiago")) }
    @After fun restoreZone() { TimeZone.setDefault(previousZone) }
    private fun limits(): JSONObject {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        return JSONObject(context.assets.open("usage/limits.view.json").bufferedReader().use { it.readText() })
    }
    @Test fun homeBefore() = home(false, "home-before")
    @Test fun homeAfter() = home(true, "home-after")
    private fun home(enabled: Boolean, name: String) {
        val observations = listOf("NODE" to QuotaState(limits(), busy = false))
        snapshot(name) { HomeScreen(HomeUiState(content = HomeContent.Empty), true, {}, quotaStrip = { QuotaStrip(observations, enabled, true, {}) }) }
        if (enabled) composeRule.onNodeWithText("38% used").assertIsDisplayed() else composeRule.onNodeWithTag("quota_strip").assertDoesNotExist()
    }
    @Test fun threadBefore() = thread(false, "thread-before")
    @Test fun threadAfter() = thread(true, "thread-after")
    private fun thread(enabled: Boolean, name: String) {
        val observations = listOf("NODE" to QuotaState(limits(), busy = false))
        snapshot(name) {
            dev.avillagran.ot3android.ui.thread.ThreadContent(
                state = dev.avillagran.ot3android.ui.thread.ThreadUiState(dev.avillagran.ot3android.core.thread.ThreadView("chat", loaded = true), title = "Quota workspace"),
                onDraftChange = {}, onSend = {}, onInterrupt = {}, onResend = {}, onDiscard = {}, onRetryStream = {}, onDismissNotice = {}, onBack = {},
                quotaStrip = { QuotaStrip(observations, enabled, false, {}) })
        }
    }
    @Test fun reducedMotionToggleLeavesNoSpaceAndOpensLimits() {
        var enabled by androidx.compose.runtime.mutableStateOf(false)
        var opens = 0
        val observations = listOf("NODE" to QuotaState(limits(), busy = false))
        snapshot("reduced-motion-hidden") { androidx.compose.foundation.layout.Column { QuotaStrip(observations, enabled, true, { opens++ }) } }
        composeRule.onNodeWithTag("quota_reveal").assertHeightIsEqualTo(0.dp)
        val start = composeRule.mainClock.currentTime
        composeRule.runOnIdle { enabled = true }
        composeRule.onNodeWithText("38% used").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(1, opens)
        org.junit.Assert.assertTrue(composeRule.mainClock.currentTime - start < 128)
        composeRule.runOnIdle { enabled = false }
        composeRule.onNodeWithTag("quota_reveal").assertHeightIsEqualTo(0.dp)
    }
    @Test fun signedOutAndUnavailableReportsNeverShowStalePercentages() {
        val reports = JSONObject("""{"providers":[
          {"driver":"codex","auth":{"status":"unauthenticated"},"usageLimits":{"windows":[{"label":"Stale auth","usedPercent":99}]}},
          {"driver":"claudeAgent","usageLimits":{"unavailable":{"reason":"probeFailed"},"windows":[{"label":"Stale probe","usedPercent":88}]}}
        ]}""")
        snapshot("auth-unavailable") { QuotaStrip(listOf("NODE" to QuotaState(reports, busy = false)), true, true, {}) }
        composeRule.onNodeWithText("Stale auth").assertDoesNotExist()
        composeRule.onNodeWithText("Stale probe").assertDoesNotExist()
        composeRule.onNodeWithText("NODE · Sign in to this provider on the host, then refresh.").assertExists()
        composeRule.onNodeWithText("NODE · Unavailable").assertExists()
    }

    @Test fun emptyIsHidden() {
        snapshot("empty") { QuotaStrip(listOf("NODE" to QuotaState(JSONObject(), busy = false)), true, true, {}) }
        composeRule.onNodeWithTag("quota_strip").assertDoesNotExist()
    }
    @Test fun offlineShowsConnectionState() {
        snapshot("offline") { QuotaStrip(listOf("NODE" to QuotaState(busy = false, offline = true)), true, true, {}) }
        composeRule.onNodeWithText("NODE · Offline").assertIsDisplayed()
    }
}
