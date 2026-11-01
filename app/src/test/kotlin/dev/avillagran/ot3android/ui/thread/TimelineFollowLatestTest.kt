package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.thread.ComposerState
import dev.avillagran.ot3android.core.thread.ThreadView
import dev.avillagran.ot3android.core.data.prefs.ReadingPosition
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-port-xhdpi")
class TimelineFollowLatestTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val data = mutableStateOf(view(20))
    private val request = mutableIntStateOf(0)
    private fun view(count: Int, latestText: String = "Message ${count - 1}") = JSONObject().put("timeline",
        JSONArray((0 until count).map { index -> JSONObject().put("id", "m$index").put("role", "assistant")
            .put("rev", if (index == count - 1) latestText else "stable")
            .put("text", if (index == count - 1) latestText else "Message $index\n".repeat(4)) }))
    private fun show(position: ReadingPosition? = null) {
        rule.setContent { Ot3Theme { Box(Modifier.height(300.dp)) {
            TimelineReader(data.value, emptyList(), initialPosition = position, followLatestRequest = request.intValue)
        } } }
    }
    private fun scrollValue() = rule.onNodeWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun incomingRowsStayAtLatestAndStreamingGrowthStaysPinned() {
        show()
        rule.onNodeWithTag(ThreadScreenTags.message("m19")).assertIsDisplayed()
        rule.runOnIdle { data.value = view(21) }
        rule.waitForIdle()
        rule.onNodeWithTag(ThreadScreenTags.message("m20")).assertIsDisplayed()
        assertEquals(0f, scrollValue())
        rule.runOnIdle { data.value = view(21, "Streaming line\n".repeat(60)) }
        rule.waitForIdle()
        assertEquals(0f, scrollValue())
    }

    @Test fun readingHistoryStaysPutUntilExplicitSendRequest() {
        show()
        rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToIndex(12)
        rule.waitForIdle()
        rule.onNodeWithTag("thread_jump_latest").assertIsDisplayed()
        rule.runOnIdle { data.value = view(21) }
        rule.waitForIdle()
        assertTrue(scrollValue() > 0f)
        rule.onNodeWithTag(ThreadScreenTags.message("m20")).assertIsNotDisplayed()
        rule.runOnIdle { request.intValue++ }
        rule.waitForIdle()
        assertEquals(0f, scrollValue())
        rule.runOnIdle { data.value = view(22) }
        rule.waitForIdle()
        rule.onNodeWithTag(ThreadScreenTags.message("m21")).assertIsDisplayed()
        assertEquals(0f, scrollValue())
    }

    @Test fun jumpToLatestResumesIncomingFollow() {
        show()
        rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToIndex(12)
        rule.onNodeWithTag("thread_jump_latest").performClick()
        rule.waitForIdle()
        rule.runOnIdle { data.value = view(21) }
        rule.waitForIdle()
        assertEquals(0f, scrollValue())
    }

    @Test fun sendButtonReturnsFromHistoryBeforeReplyArrives() {
        var sent = 0
        val connected = ConnectionState.Connected(ServerDescriptor.from(JSONObject().put("environmentId", "env")))
        rule.setContent { Ot3Theme {
            ThreadContent(ThreadUiState(ThreadView("chat", loaded = true, timeline = data.value),
                connection = connected, composer = ComposerState(draft = "Send from history")),
                {}, { sent++ }, {}, {}, {}, {}, {}, {})
        } }
        rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToIndex(12)
        rule.onNodeWithTag("thread_jump_latest").assertIsDisplayed()
        rule.onNodeWithTag(ThreadScreenTags.SEND).performClick()
        rule.waitForIdle()
        assertEquals(1, sent)
        assertEquals(0f, scrollValue())
        rule.runOnIdle { data.value = view(21) }
        rule.waitForIdle()
        assertEquals(0f, scrollValue())
    }

    @Test fun historyAnchorSurvivesRecreationAndThenIncomingRows() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Ot3Theme { Box(Modifier.height(300.dp)) { TimelineReader(data.value, emptyList()) } } }
        rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToIndex(12)
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { data.value = view(21) }
        rule.waitForIdle()
        rule.onNodeWithTag(ThreadScreenTags.message("m7")).assertIsDisplayed()
        assertTrue(scrollValue() > 0f)
    }

    @Test fun restoredHistoryIsNotOverriddenByIncomingRows() {
        show(ReadingPosition("m7", 12, 25))
        rule.waitForIdle()
        assertTrue(scrollValue() > 0f)
        rule.runOnIdle { data.value = view(21) }
        rule.waitForIdle()
        rule.onNodeWithTag(ThreadScreenTags.message("m7")).assertIsDisplayed()
        rule.onNodeWithTag(ThreadScreenTags.message("m20")).assertIsNotDisplayed()
        assertTrue(scrollValue() > 0f)
    }

    @Test fun cachedWindowDoesNotOverwriteAnAnchorMissingUntilRefresh() {
        val full = view(20)
        val all = full.getJSONArray("timeline")
        data.value = JSONObject().put("timeline", JSONArray((15 until 20).map { all.getJSONObject(it) }))
        val refreshing = mutableStateOf(true)
        val saved = mutableListOf<ReadingPosition>()
        rule.setContent { Ot3Theme { Box(Modifier.height(300.dp)) {
            TimelineReader(data.value, emptyList(), initialPosition = ReadingPosition("m2", 17, 0),
                refreshing = refreshing.value, onPosition = { saved.add(it) })
        } } }
        rule.waitForIdle()
        assertTrue(saved.isEmpty())
        rule.runOnIdle { data.value = full; refreshing.value = false }
        rule.waitForIdle()
        rule.onNodeWithTag(ThreadScreenTags.message("m2")).assertIsDisplayed()
    }
}
