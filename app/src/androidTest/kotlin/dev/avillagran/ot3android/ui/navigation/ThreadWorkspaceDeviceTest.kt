package dev.avillagran.ot3android.ui.navigation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import dev.avillagran.ot3android.ui.thread.ThreadScreenTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real screens/ViewModels and Rust projections over the debug-only offline server. */
@RunWith(AndroidJUnit4::class)
class ThreadWorkspaceDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<WorkspaceFixtureActivity>()

    @Test fun rotationCollapseAndRecreationKeepDraftReadingPositionAndOneStream() {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(NavTags.BAR).assertDoesNotExist()
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performTextInput("Borrador de rotación")
        rule.activityRule.scenario.onActivity { it.currentFocus?.clearFocus() }
        rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToIndex(30)
        rule.onNodeWithTag(ThreadScreenTags.message("m29")).assertIsDisplayed()
        val fixture = rule.activity.fixture
        kotlinx.coroutines.runBlocking {
            fixture.transport.emitThread("chat", dev.avillagran.ot3android.core.transport.ThreadUpdates.event(
                org.json.JSONObject().put("kind", "event").put("event", org.json.JSONObject()
                    .put("type", "message.updated").put("payload", org.json.JSONObject()
                        .put("id", "m59").put("role", "assistant").put("text", "Streaming update while reading older history")
                        .put("streaming", true))), cursor = 2))
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ThreadScreenTags.message("m29")).assertIsDisplayed()
        rule.onNodeWithTag("thread_jump_latest").assertIsDisplayed()
        val streamCount = { fixture.transport.streamOpens.count { it.threadId == "chat" } }
        rule.runOnIdle {
            assertEquals(1, streamCount())
            assertEquals(1, fixture.transport.log.count { it.method == "boundedThread" })
        }
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val density = rule.activity.resources.displayMetrics.density
        val sidebarList = rule.onNodeWithTag(HomeTags.LIST).fetchSemanticsNode().boundsInRoot
        val chatViewport = rule.onNodeWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNode().boundsInRoot
        assertEquals("Cutout is consumed once before the split, preserving 280 dp content", 280f * density, sidebarList.width, density)
        assertEquals("Chat must not pay the cutout inset a second time", sidebarList.right, chatViewport.left, density)
        rule.onNodeWithTag("thread_sidebar_toggle").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(ThreadScreenTags.COMPOSER).fetchSemanticsNodes().any {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.EditableText) { androidx.compose.ui.text.AnnotatedString("") }.text == "Borrador de rotación"
            }
        }
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains("Borrador de rotación")
        rule.onNodeWithTag("thread_sidebar_toggle").performClick()
        rule.onNodeWithTag(HomeTags.row("node/chat")).performClick() // Reselecting is a no-op.
        rule.onNodeWithTag(ThreadScreenTags.message("m29")).assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(1, streamCount())
            assertEquals(1, fixture.transport.log.count { it.method == "boundedThread" })
        }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(ThreadScreenTags.COMPOSER).fetchSemanticsNodes().any {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.EditableText) { androidx.compose.ui.text.AnnotatedString("") }.text == "Borrador de rotación"
            }
        }
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains("Borrador de rotación")
        rule.onNodeWithTag(ThreadScreenTags.message("m29")).assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(1, streamCount())
            assertEquals(1, fixture.transport.log.count { it.method == "boundedThread" })
        }
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        rule.onNodeWithTag("thread_sidebar_toggle").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag(NavTags.BAR).assertIsDisplayed()
    }
}
