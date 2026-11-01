package dev.avillagran.ot3android.ui.thread

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.thread.reader.PlanLifecycle
import dev.avillagran.ot3android.ui.thread.reader.PlanOperation
import dev.avillagran.ot3android.ui.thread.reader.TranscriptActions
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ChatActionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val plan = JSONObject("""{"id":"p1","threadId":"chat","status":"active","title":"Original title","markdown":"# Original title\n\n## Summary\n\n- First\n- Second\n"}""")

    @Test fun sharePreservesOriginalMarkdownIncludingHeadingsAndTrailingNewline() {
        val intent = planShareIntent(plan)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/markdown", intent.type)
        assertEquals(plan.getString("markdown"), intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("Original title", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test fun planUsesComposerFeedbackAndUnknownReceiptHidesAllMutationButtons() {
        var unknown by mutableStateOf(false)
        val requests = mutableListOf<Pair<String, Boolean>>()
        rule.setContent { Ot3Theme {
            PlanLifecycle(plan, plan, TranscriptActions(planDraft = "Refine the tests",
                followUpPlan = { _, text, new -> requests += text to new },
                planOperation = if (unknown) PlanOperation("p1", uncertain = true) else null))
        } }
        rule.onNodeWithText("Send feedback").performClick()
        assertEquals(listOf("Refine the tests" to false), requests)
        rule.onNodeWithText("In new thread").performClick()
        assertEquals("Refine the tests" to true, requests.last())
        rule.runOnIdle { unknown = true }
        rule.onNodeWithText("Send feedback").assertDoesNotExist()
        rule.onNodeWithText("In new thread").assertDoesNotExist()
    }

    @Test fun historicalPlanCannotActOnAnotherReadyPlan() {
        rule.setContent { Ot3Theme {
            PlanLifecycle(plan, JSONObject(plan.toString()).put("id", "other"),
                TranscriptActions(followUpPlan = { _, _, _ -> error("historical plan") }))
        } }
        rule.onNodeWithText("Implement plan").assertDoesNotExist()
        rule.onNodeWithText("In new thread").assertDoesNotExist()
    }
}
