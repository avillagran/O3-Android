package dev.avillagran.ot3android.ui.settings.maintenance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w600dp-h1200dp")
class TaskEditorTest {
    @get:Rule val rule = createComposeRule()
    private fun task() = JSONObject("""{"id":"task","title":"Hook task","prompt":"Run","projectId":"p","modelSelection":{},"schedule":{"type":"webhook","signature":{"header":"x-signature","encoding":"base64","prefix":"","secret":"must-not-display"},"filters":[],"dedupe":{"enabled":true}},"workspaceStrategy":{"type":"root"}}""")

    @Test fun newSigningSecretIsMaskedSentOnceAndClearedOnRejection() {
        val original = task().apply { remove("id"); getJSONObject("schedule").put("signature", JSONObject.NULL) }
        var submitted: JSONObject? = null
        rule.setContent { Ot3Theme { TaskEditor(original, {}) { args ->
            submitted = JSONObject(args.toString()); "Rejected fixture"
        } } }
        rule.onNodeWithText("HMAC-SHA256 signature").performScrollTo().performClick()
        rule.onNodeWithText("Signing secret").performScrollTo().performTextInput("fixture-signing-value")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Rejected fixture").performScrollTo().assertIsDisplayed()
        rule.runOnIdle {
            val signature = submitted!!.getJSONObject("schedule").getJSONObject("signature")
            assertEquals("fixture-signing-value", signature.getString("secret"))
            assertEquals("hex", signature.getString("encoding"))
            assertEquals("sha256=", signature.getString("prefix"))
        }
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun saveWaitsForAcknowledgementAndKeepsRejectionVisible() {
        val finish = CompletableDeferred<String?>()
        var dismissed = false
        var payload: JSONObject? = null
        rule.setContent { Ot3Theme { TaskEditor(task(), { dismissed = true }) { args ->
            payload = JSONObject(args.toString()); finish.await()
        } } }
        rule.onNodeWithText("must-not-display").assertDoesNotExist()
        rule.onNodeWithText("Save").performClick()
        rule.runOnIdle {
            assertFalse(dismissed)
            val schedule = payload!!.getJSONObject("schedule")
            assertTrue(schedule.has("filters")); assertTrue(schedule.has("dedupe"))
            assertFalse(schedule.getJSONObject("signature").has("secret"))
            finish.complete("Task was not saved.")
        }
        rule.onNodeWithText("Task was not saved.").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertFalse(dismissed) }
    }
}
