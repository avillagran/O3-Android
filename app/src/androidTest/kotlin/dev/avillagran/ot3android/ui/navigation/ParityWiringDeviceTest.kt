package dev.avillagran.ot3android.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import dev.avillagran.ot3android.ui.thread.ThreadScreenTags
import org.junit.Rule
import org.junit.Test

class ParityWiringDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    // Espresso's closeSoftKeyboard returns before the IME finish-composition callback
    // is necessarily delivered to Compose. Wait for the real product gate; a control
    // that stays disabled still fails within the bounded deadline.
    private fun awaitEnabled(node: () -> SemanticsNodeInteraction): SemanticsNodeInteraction {
        rule.waitUntil(5_000) {
            !node().fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
        return node().assertIsEnabled()
    }

    @Test fun interruptedAttachmentCommandReopensWithoutReplayAndRetriesIdenticalBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = context.noBackupFilesDir.resolve("fixture-composer-attachments").apply { mkdirs() }
        val key = java.security.MessageDigest.getInstance("SHA-256").digest("node/chat".toByteArray()).joinToString("") { "%02x".format(it) }
        val file = directory.resolve(key + ".json")
        val descriptor = org.json.JSONObject().put("id", "fixture-attachment").put("name", "example.txt").put("mimeType", "text/plain").put("sizeBytes", 4)
        file.writeText(org.json.JSONObject().put("authority", "fixture-server").put("command", "")
            .put("attachments", org.json.JSONArray().put(org.json.JSONObject().put("id", "upload-attempt").put("name", "example.txt").put("descriptor", descriptor.toString()))).toString())
        fun intent() = Intent(context, WorkspaceFixtureActivity::class.java).putExtra("parity", true).putExtra("request", "editor")
        fun openThread() {
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.COMPOSER).fetchSemanticsNodes().isNotEmpty() }
        }
        var payload = ""
        try {
            ActivityScenario.launch<WorkspaceFixtureActivity>(intent()).use { scenario ->
                openThread()
                scenario.onActivity { it.fixture.queueUnknownFirst = true }
                rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performTextReplacement("Attachment acceptance")
                scenario.onActivity { it.currentFocus?.clearFocus() }
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                awaitEnabled { rule.onNodeWithTag(ThreadScreenTags.SEND) }.performClick()
                rule.waitUntil(10_000) { var ready = false; scenario.onActivity { activity ->
                    val calls = activity.fixture.transport.calls.filter { it.fn == "orchestration.dispatchCommand" }
                    ready = calls.size == 1
                    if (ready) payload = calls.single().args.toString()
                }; ready }
                rule.waitUntil(10_000) { rule.onAllNodesWithText("Send outcome unknown. Reconcile the existing command before editing or sending again.").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertIsNotEnabled()
                rule.onNodeWithTag(ThreadScreenTags.SEND).assertIsNotEnabled()
                org.junit.Assert.assertEquals(payload, org.json.JSONObject(file.readText()).getString("command"))
            }
            ActivityScenario.launch<WorkspaceFixtureActivity>(intent()).use { scenario ->
                openThread()
                scenario.onActivity { org.junit.Assert.assertTrue(it.fixture.transport.calls.none { call -> call.fn == "orchestration.dispatchCommand" }) }
                rule.onNodeWithText("Resend").performClick()
                rule.waitUntil(10_000) { var sent = false; scenario.onActivity {
                    val calls = it.fixture.transport.calls.filter { call -> call.fn == "orchestration.dispatchCommand" }
                    sent = calls.size == 1
                    if (sent) org.junit.Assert.assertEquals(payload, calls.single().args.toString())
                }; sent }
                rule.waitUntil(10_000) { org.json.JSONObject(file.readText()).optString("command").isEmpty() }
                org.junit.Assert.assertEquals(0, org.json.JSONObject(file.readText()).getJSONArray("attachments").length())
            }
        } finally { file.delete() }
    }

    @Test fun composerEntryPointsAcrossBothOrientationsAndThemes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        kotlinx.coroutines.runBlocking {
            val graph = (context.applicationContext as dev.avillagran.ot3android.Ot3Application).graph
            graph.generalClientPreferences.update("quickReplyLanguage", "en")
            graph.generalClientPreferences.update("quickRepliesButton", true)
            graph.generalClientPreferences.reset("quickReplies.en")
        }
        for (dark in listOf(false, true)) for (landscape in listOf(false, true)) {
            ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
                .putExtra("parity", true).putExtra("request", "editor").putExtra("dark", dark).putExtra("landscape", landscape)).use { scenario ->
                rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
                rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.COMPOSER).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performTextReplacement("Parity draft")
                scenario.onActivity { it.currentFocus?.clearFocus() }
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                rule.onNodeWithTag("composer_actions").performClick()
                awaitEnabled { rule.onNodeWithText("Undo") }.performClick()
                rule.onNodeWithTag("composer_actions").performClick()
                rule.onNodeWithText("Redo").performClick()
                rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextEquals("Parity draft")
                rule.onNodeWithTag("composer_actions").performClick()
                rule.onNodeWithText("Quick replies").performClick()
                rule.waitUntil(5_000) { rule.onAllNodesWithText("Continue.").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithText("Continue.").performScrollTo().performClick()
                rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextEquals("Continue.")
                rule.onNodeWithTag("composer_expand").performClick()
                rule.onNodeWithTag("composer_expanded").assertIsDisplayed()
                rule.onNodeWithTag("composer_dialog_back").performClick()
                rule.onNodeWithTag("composer_expanded").assertDoesNotExist()
                rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextEquals("Continue.")
            }
        }
    }
}
