package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class QuestionCardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun requests(capability: String = "live") = JSONArray("""[{"requestId":"input","responseCapability":"$capability","dismissible":true,"questions":[{"id":"one","header":"First question","question":"Choose **one**","allowCustomAnswer":false,"options":[{"label":"Alpha","value":"a"},{"label":"Beta","value":"b"}]},{"id":"two","header":"Second question","question":"Choose several","multiSelect":true,"allowCustomAnswer":false,"options":[{"label":"Gamma","value":"g"},{"label":"Delta","value":"d"}]}]}]""")

    @Test fun singleSelectionAdvancesAndMultiSelectionSubmitsOneCompleteAnswerSet() {
        val sent = mutableListOf<JSONObject>()
        rule.setContent { Ot3Theme { QuestionCard(requests(), true, null, emptySet(), renderMarkdown = { JSONArray() }) { _, action, answers ->
            assertEquals("respond", action); sent += answers
        } } }
        rule.onNodeWithText("Alpha").performClick()
        rule.waitUntil(3_000) { rule.onAllNodesWithText("Second question").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Gamma").performClick()
        rule.onNodeWithText("Delta").performClick()
        rule.runOnIdle { assertEquals(0, sent.size) }
        rule.onNodeWithContentDescription("Send").performClick()
        rule.runOnIdle {
            assertEquals("a", sent.single().getString("one"))
            assertEquals(listOf("g", "d"), (0..1).map { sent.single().getJSONArray("two").getString(it) })
        }
    }

    @Test fun dismissalIsExplicitAndDoesNotSupplyInventedAnswers() {
        var action: String? = null
        var response: JSONObject? = null
        rule.setContent { Ot3Theme { QuestionCard(requests(), true, null, emptySet(), renderMarkdown = { JSONArray() }) { id, a, answers ->
            assertEquals("input", id); action = a; response = answers
        } } }
        rule.onNodeWithContentDescription("Dismiss question without answering").performClick()
        rule.runOnIdle { assertEquals("dismiss", action); assertEquals(0, response!!.length()) }
    }

    @Test fun nonResumableQuestionCannotBeAnsweredButKeepsNativeDismissibility() {
        rule.setContent { Ot3Theme { QuestionCard(requests("not_resumable"), true, null, emptySet(), renderMarkdown = { JSONArray() }) { _, _, _ -> error("cannot respond") } } }
        rule.onNodeWithContentDescription("Dismiss question without answering").assertIsEnabled()
        rule.onNodeWithContentDescription("Next question").assertIsNotEnabled()
        rule.onNodeWithText("This question can no longer be answered.").assertIsDisplayed()
    }
}
