package dev.avillagran.ot3android.ui.thread.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.thread.ThreadScreenTags
import dev.avillagran.ot3android.ui.thread.TimelineReader
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
class TranscriptReaderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun findingOlderMessageKeepsAnchorWhileNewResponseStreams() {
        fun view(live: String) = JSONObject().put("timeline", JSONArray((0..25).map {
            JSONObject().put("id", "m$it").put("role", "assistant").put("rev", if (it == 25) live else "1")
                .put("text", if (it == 2) "unique needle" else if (it == 25) live else "message $it\n".repeat(5))
        }))
        val data = mutableStateOf(view("live"))
        rule.setContent { Ot3Theme { Box(Modifier.height(600.dp)) { TimelineReader(data.value, emptyList()) } } }
        rule.onNodeWithTag("transcript_find").performClick()
        rule.onNodeWithTag("transcript_query").performTextInput("needle")
        rule.onNodeWithTag("transcript_next").performClick()
        rule.onNodeWithTag(ThreadScreenTags.message("m2")).assertIsDisplayed()
        rule.runOnIdle { data.value = view("live streaming\n".repeat(20)) }
        rule.onNodeWithTag(ThreadScreenTags.message("m2")).assertIsDisplayed()
        rule.onNodeWithTag("thread_jump_latest").assertIsDisplayed()
    }
    @Test fun loadOlderBoundaryIsAButtonThatRequestsOlderHistory() {
        var requests = 0
        val data = JSONObject().put("hasMoreHistory", true).put("timeline", JSONArray().put(
            JSONObject().put("id", "m").put("role", "assistant").put("rev", "1").put("text", "latest")))
        rule.setContent { Ot3Theme { Box(Modifier.height(600.dp)) { TimelineReader(data, emptyList(), onLoadOlder = { requests++ }) } } }
        rule.onNodeWithTag("thread_load_older").assertHasClickAction().performClick()
        rule.runOnIdle { assertEquals(1, requests) }
    }
    @Test fun quoteAndNavigationRequireCallbacksAndNativeReadyPlan() {
        var quote = ""
        var target = ""
        val rows = JSONArray("""[
            {"id":"u","role":"user","text":"prompt"},
            {"id":"a","role":"subagents","subagents":[{"id":"child","title":"Child","childThreadId":"other"}]}
        ]""")
        rule.setContent { Ot3Theme { TimelineReader(JSONObject().put("timeline", rows), emptyList(),
            actions = TranscriptActions(quote = { quote = it }, openThread = { target = it })) } }
        rule.onNodeWithTag("transcript_quote_u").performClick()
        rule.onNodeWithTag("transcript_agent_child").performClick()
        assertEquals("prompt", quote); assertEquals("other", target)
    }
    @Test fun planActionsRequireReadyIdentityAndNeverRetryUncertainDelivery() {
        val plan = JSONObject("""{"id":"plan","threadId":"thread","status":"active"}""")
        val ready = mutableStateOf<JSONObject?>(JSONObject("""{"id":"other","threadId":"thread"}"""))
        val operation = mutableStateOf<PlanOperation?>(null)
        var sends = 0
        var retries = 0
        rule.setContent { Ot3Theme { PlanLifecycle(plan, ready.value, TranscriptActions(
            followUpPlan = { artifact, feedback, newThread ->
                assertEquals("plan", artifact.getString("id")); assertEquals("", feedback); assertFalse(newThread); sends++
            }, planOperation = operation.value)) } }
        rule.onNodeWithText("Implement plan").assertDoesNotExist()
        rule.runOnIdle { ready.value = JSONObject(plan.toString()) }
        rule.onNodeWithText("Implement plan").performClick()
        assertEquals(1, sends)
        rule.runOnIdle { operation.value = PlanOperation("plan", uncertain = true, failed = true, retry = { retries++ }) }
        rule.onNodeWithText("Retry operation").assertDoesNotExist()
        rule.onNodeWithText("Implement plan").assertDoesNotExist()
        assertEquals(0, retries)
    }

    @Test fun assistantQuoteAndForkStayUnavailableWithoutTerminalEligibility() {
        val rows = JSONArray("""[
            {"id":"commentary","role":"assistant","text":"working","streaming":false,"forkRunId":"run"},
            {"id":"final","role":"assistant","text":"done","streaming":false,"forkRunId":"run"},
            {"id":"live","role":"assistant","text":"streaming","streaming":true}
        ]""")
        rule.setContent { Ot3Theme { TimelineReader(JSONObject().put("timeline", rows), emptyList(),
            actions = TranscriptActions(quote = { error("unbound eligibility") })) } }
        for (id in listOf("commentary", "final", "live")) {
            rule.onNodeWithTag("transcript_quote_$id").assertDoesNotExist()
        }
        rule.onNodeWithText("Fork").assertDoesNotExist()
    }

}
