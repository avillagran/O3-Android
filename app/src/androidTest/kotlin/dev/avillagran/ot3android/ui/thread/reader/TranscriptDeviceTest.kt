package dev.avillagran.ot3android.ui.thread.reader

import android.view.inspector.WindowInspector
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.thread.ThreadScreenTags
import dev.avillagran.ot3android.ui.thread.TimelineReader
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TranscriptDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun findKeepsOlderRowDuringStreamAndReturnsToLatest() {
        fun view(text: String) = JSONObject().put("timeline", JSONArray((0..30).map { i ->
            JSONObject().put("id", "m$i").put("role", "assistant").put("rev", if (i == 30) text else "stable")
                .put("text", if (i == 2) "unique needle" else if (i == 30) text else "Message $i\n".repeat(6))
        }))
        val data = mutableStateOf(view("live"))
        ActivityScenario.launch(WorkspaceFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { Ot3Theme { TimelineReader(data.value, emptyList()) } } }
            rule.onNodeWithTag("transcript_find").performClick()
            rule.onNodeWithTag("transcript_query").performTextInput("needle")
            rule.onNodeWithTag("transcript_next").performClick()
            rule.onNodeWithTag(ThreadScreenTags.message("m2")).assertIsDisplayed()
            rule.runOnIdle { data.value = view("streaming\n".repeat(30)) }
            rule.onNodeWithTag(ThreadScreenTags.message("m2")).assertIsDisplayed()
            rule.onNodeWithTag("transcript_find").performClick()
            rule.onNodeWithTag("thread_jump_latest").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("thread_jump_latest").assertDoesNotExist()
        }
    }

    @Test fun secretIsSecureClearedAndSentOnlyOnceWithoutLogging() {
        var calls = 0
        var retained: JSONObject? = null
        val base = FakeT3Transport()
        val transport = object : T3Transport by base {
            override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
                assertEquals("secrets.answerRequest", fn)
                assertEquals("fixture-value", args.getJSONObject("answer").getString("secret"))
                calls++; retained = args
                return null
            }
        }
        val controller = TranscriptSecrets(transport)
        val request = JSONObject("""{"threadId":"t","turnItemId":"s","label":"Token","status":"pending","local":true}""")
        ActivityScenario.launch(WorkspaceFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { Ot3Theme { SecretRequestCard(request, controller) } } }
            rule.onNodeWithTag("transcript_secret_answer").performClick()
            rule.onNodeWithTag("transcript_secret_input").performTextInput("discard-me")
            scenario.recreate()
            scenario.onActivity { it.setContent { Ot3Theme { SecretRequestCard(request, controller) } } }
            rule.onNodeWithTag("transcript_secret_input").assertDoesNotExist()
            assertEquals(0, calls)
            rule.onNodeWithTag("transcript_secret_answer").performClick()
            rule.onNodeWithTag("transcript_secret_input").assertTextEquals("")
            rule.runOnIdle {
                assertTrue(WindowInspector.getGlobalWindowViews().any { view ->
                    ((view.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
                })
            }
            rule.onNodeWithTag("transcript_secret_input").performTextInput("fixture-value")
            rule.onNodeWithTag("transcript_secret_save").performClick()
            rule.onNodeWithTag("transcript_secret_input").assertDoesNotExist()
            rule.onNodeWithTag("transcript_secret_answer").assertDoesNotExist()
            scenario.recreate()
            scenario.onActivity { it.setContent { Ot3Theme { SecretRequestCard(request, controller) } } }
            rule.onNodeWithTag("transcript_secret_answer").assertDoesNotExist()
            assertEquals(1, calls)
            assertFalse(retained.toString().contains("fixture-value"))
            assertTrue(base.log.isEmpty())
        }
    }
}
