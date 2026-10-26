package dev.avillagran.ot3android.ui.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.thread.ComposerState
import dev.avillagran.ot3android.core.thread.PendingCommand
import dev.avillagran.ot3android.core.thread.RuntimeRequest
import dev.avillagran.ot3android.core.thread.ThreadConnection
import dev.avillagran.ot3android.core.thread.ThreadMessage
import dev.avillagran.ot3android.core.thread.ThreadView
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val connected = ConnectionState.Connected(ServerDescriptor.from(JSONObject().put("environmentId", "env-1")))

    private val view = ThreadView(
        threadId = "t1",
        loaded = true,
        title = "Fix the build",
        messages = listOf(
            ThreadMessage("m1", "user", "Why does CI fail?", 1_792_890_000_000, streaming = false, rev = "a"),
            ThreadMessage("m2", "assistant", "Looking at the log", 1_792_890_001_000, streaming = true, rev = "b"),
        ),
        working = true,
        activeRunId = "r1",
        pendingRequests = listOf(RuntimeRequest("q1", "command", "./gradlew clean")),
        connectionState = ThreadConnection.Live,
    )

    private fun setContent(state: () -> ThreadUiState, sent: MutableList<Unit> = mutableListOf(), onDraft: (String) -> Unit = {}) {
        composeRule.setContent {
            Ot3Theme {
                ThreadContent(
                    state = state(),
                    onDraftChange = onDraft,
                    onSend = { sent += Unit },
                    onInterrupt = {},
                    onResend = {},
                    onDiscard = {},
                    onRetryStream = {},
                    onDismissNotice = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun rendersMessagesStreamingWorkingAndThePendingRequest() {
        setContent({ ThreadUiState(view = view, title = view.title, connection = connected) })

        composeRule.onNodeWithTag(ThreadScreenTags.TITLE).assertTextContains("Fix the build")
        composeRule.onNodeWithTag(ThreadScreenTags.message("m1")).assertIsDisplayed()
        composeRule.onNodeWithTag(ThreadScreenTags.message("m2")).assertIsDisplayed()
        composeRule.onNodeWithTag(ThreadScreenTags.streaming("m2"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ThreadScreenTags.message("m2"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Typing"))
        composeRule.onNodeWithTag(ThreadScreenTags.WORKING, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ThreadScreenTags.INTERRUPT).assertIsEnabled()
        composeRule.onNodeWithTag(ThreadScreenTags.request("q1")).assertIsDisplayed()
    }

    @Test
    fun sendIsEnabledOnlyWithTextWhileConnected() {
        var state by mutableStateOf(ThreadUiState(view = view, connection = connected))
        val sent = mutableListOf<Unit>()
        setContent({ state }, sent) { text -> state = state.copy(composer = state.composer.copy(draft = text)) }

        composeRule.onNodeWithTag(ThreadScreenTags.SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(ThreadScreenTags.COMPOSER).performTextInput("Try again")
        composeRule.onNodeWithTag(ThreadScreenTags.SEND).assertIsEnabled().performClick()
        assertEquals(1, sent.size)

        state = state.copy(connection = ConnectionState.Unreachable("unreachable", nextRetryAt = null))
        composeRule.onNodeWithTag(ThreadScreenTags.SEND).assertIsNotEnabled()
    }

    @Test
    fun theFieldIsLockedWhileSending() {
        setContent({ ThreadUiState(view = view, connection = connected, composer = ComposerState(draft = "Hello", sending = true)) })

        composeRule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertIsNotEnabled()
        composeRule.onNodeWithTag(ThreadScreenTags.SENDING).assertIsDisplayed()
    }

    @Test
    fun anUnconfirmedCommandBlocksSendingUntilResolved() {
        val pending = PendingCommand("ot3a-1", "env-1", "t1", """{"text":"Hello"}""", 1, "unknown")
        setContent({
            ThreadUiState(view = view, connection = connected, composer = ComposerState(draft = "Next", unconfirmed = listOf(pending)))
        })

        composeRule.onNodeWithTag(ThreadScreenTags.SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(ThreadScreenTags.resend("ot3a-1")).assertIsEnabled()
    }

    @Test
    fun showsStaleAndUnconfirmedCommands() {
        val pending = PendingCommand("ot3a-1", "env-1", "t1", """{"text":"Hello"}""", 1, "unknown")
        setContent({
            ThreadUiState(view = view.copy(stale = true), connection = connected, composer = ComposerState(unconfirmed = listOf(pending)))
        })

        composeRule.onNodeWithTag(ThreadScreenTags.STALE, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ThreadScreenTags.unconfirmed("ot3a-1")).assertIsDisplayed()
        composeRule.onNodeWithTag(ThreadScreenTags.resend("ot3a-1")).assertIsEnabled()
    }
}
