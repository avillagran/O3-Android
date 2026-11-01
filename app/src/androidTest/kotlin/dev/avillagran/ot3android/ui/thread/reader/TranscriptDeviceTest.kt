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

    @Test fun positionPreviewKeepsViewportStableAcrossReadingAnchors() {
        val data = JSONObject().put("timeline", JSONArray((0..30).map { i ->
            JSONObject().put("id", "m$i").put("role", "assistant").put("text", "Message $i\n".repeat(6))
        }))
        ActivityScenario.launch(WorkspaceFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { Ot3Theme { TimelineReader(data, emptyList()) } } }
            val timeline = rule.onNodeWithTag(ThreadScreenTags.TIMELINE)
            val latestBounds = timeline.fetchSemanticsNode().boundsInRoot
            timeline.performScrollToIndex(12)
            assertEquals(latestBounds, timeline.fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithTag("thread_jump_latest").performClick()
            assertEquals(latestBounds, timeline.fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun landscapeImeKeepsMultilineDraftAndReaderReachable() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val draft = "Draft line\n".repeat(12)
        ActivityScenario.launch<WorkspaceFixtureActivity>(android.content.Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("rich", true)).use { scenario ->
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(dev.avillagran.ot3android.ui.home.HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(dev.avillagran.ot3android.ui.home.HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.COMPOSER).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performClick().performTextReplacement(draft)
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitUntil(10_000) {
                var ready = false
                scenario.onActivity { ready = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && it.hasWindowFocus() }
                ready
            }
            // SetText alone does not request a real keyboard. Serve the focused editor
            // in the recreated window before requesting the platform IME.
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performClick().assertIsFocused()
            scenario.onActivity {
                androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            rule.waitUntil(10_000) {
                var ready = false
                scenario.onActivity {
                    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                    ready = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true &&
                        insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom > 0
                }
                ready
            }
            assertTrue("The reader must retain a viewport above the landscape IME: " + rule.onRoot().printToString(maxDepth = 6),
                rule.onNodeWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNode().boundsInRoot.height > 0f)
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains(draft)
            rule.onNodeWithTag("thread_overflow").performClick()
            rule.onNodeWithTag("composer_overflow_options").assertIsDisplayed()
        }
    }

    @Test fun changingSecretConnectionClearsPrivateInput() {
        val first = FakeT3Transport()
        val second = FakeT3Transport()
        val controller = mutableStateOf<TranscriptSecrets?>(TranscriptSecrets(first))
        val request = JSONObject("""{"threadId":"t","turnItemId":"s","label":"Token","status":"pending","local":true}""")
        ActivityScenario.launch(WorkspaceFixtureActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { Ot3Theme { SecretRequestCard(request, controller.value) } } }
            rule.onNodeWithTag("transcript_secret_answer").performClick()
            rule.onNodeWithTag("transcript_secret_input").performTextInput("discard-on-disconnect")
            rule.runOnIdle { controller.value = null }
            rule.onNodeWithTag("transcript_secret_input").assertDoesNotExist()
            rule.runOnIdle { controller.value = TranscriptSecrets(second) }
            rule.onNodeWithTag("transcript_secret_answer").performClick()
            rule.onNodeWithTag("transcript_secret_input").assertTextEquals("")
            assertTrue(first.log.isEmpty())
            assertTrue(second.log.isEmpty())
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
