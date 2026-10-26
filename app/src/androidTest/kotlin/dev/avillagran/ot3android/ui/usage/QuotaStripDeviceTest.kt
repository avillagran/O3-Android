package dev.avillagran.ot3android.ui.usage

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import dev.avillagran.ot3android.ui.thread.ThreadScreenTags
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Actual Home/thread/Usage with a fake transport and the real native quota reducer. */
class QuotaStripDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun resetPreference() = runBlocking { context.appGraph.appearancePreferences.setProviderUsage(false) }
    @After fun restorePreference() = resetPreference()

    @Test fun toggleNavigationRecreationDraftAndSharedStream() {
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("quota_strip").assertDoesNotExist()
            scenario.onActivity { assertEquals(0, it.fixture.transport.serverConfigCollectors().value) }
            rule.onNodeWithTag(HomeTags.OVERFLOW).performClick()
            rule.onNodeWithTag("quota_toggle").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("38% used").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("38% used").assertIsDisplayed()
            scenario.onActivity { assertEquals(1, it.fixture.transport.serverConfigCollectors().value) }
            scenario.recreate()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("38% used").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.COMPOSER).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("38% used").assertIsDisplayed()
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performTextInput("Quota draft retained")
            rule.onNodeWithTag("thread_overflow").performClick()
            rule.onNodeWithTag("quota_toggle").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("quota_strip").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains("Quota draft retained")
            rule.onNodeWithTag("thread_overflow").performClick()
            rule.onNodeWithTag("quota_toggle").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("38% used").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitForIdle()
            // Landscape has a live Home sidebar too, with the same shared quota owner.
            scenario.onActivity { assertEquals(1, it.fixture.transport.serverConfigCollectors().value) }
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains("Quota draft retained")
            rule.onAllNodesWithText("38% used").onLast().performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("usage_tab_limits").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("usage_tab_limits").assertIsSelected()
            scenario.onActivity { assertEquals(1, it.fixture.transport.serverConfigCollectors().value) }
            rule.onNodeWithText("Back").performClick()
            rule.waitForIdle()
            scenario.onActivity {
                assertEquals(1, it.fixture.transport.serverConfigCollectors().value)
                assertEquals(1, it.fixture.transport.calls.count { call -> call.fn == "server.refreshProviders" })
                assertTrue(it.fixture.transport.calls.none { call -> call.fn == "orchestration.dispatchCommand" })
            }
        }
    }
}
