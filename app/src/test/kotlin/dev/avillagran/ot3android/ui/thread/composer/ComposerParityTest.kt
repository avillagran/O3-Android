package dev.avillagran.ot3android.ui.thread.composer

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.composer.*
import dev.avillagran.ot3android.ui.thread.ComposerEditor
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ComposerParityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = ComposerScope("host", "project", "thread")
    private fun initial() = ComposerSnapshot(scope, text = "Look at the file", selection = ComposerSelection(16, 16),
        attachments = listOf(ComposerAttachment("file", "notes.md", UploadStatus.Ready, "descriptor")))
    @Test fun compactCaptureUsesOverflowAndKeepsConsentAfterMenuCloses() {
        val requests = mutableListOf<ComposerRequest>()
        rule.setContent { Ot3Theme {
            val compact = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply { screenHeightDp = 360 }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides compact) {
                ComposerParity(ComposerSnapshot(scope), setOf(ComposerAction.Attach), { _, _, _, _ -> }, requests::add)
            }
        } }
        rule.onNodeWithTag("capture_dictate").assertDoesNotExist()
        rule.onNodeWithTag("composer_actions").performClick()
        rule.onNodeWithTag("capture_menu").performClick()
        rule.onNodeWithTag("capture_dictate").performClick()
        rule.onNodeWithText("Start dictation").assertIsDisplayed()
        rule.onNodeWithText("Insert into draft").assertIsNotEnabled()
        rule.runOnIdle { assertTrue(requests.isEmpty()) }
    }

    @Test fun blockedEmptyDraftDoesNotPretendToBeSendingAndBusyIsExplicit() {
        var state by mutableStateOf(ComposerSnapshot(scope, loading = true))
        rule.setContent { Ot3Theme { ComposerParity(state, emptySet(), { _, _, _, _ -> }, {}) } }
        rule.onNodeWithTag("thread_sending").assertDoesNotExist()
        rule.onNodeWithTag("thread_send").assertIsNotEnabled()
        rule.onNodeWithTag("composer_busy").assertDoesNotExist()
        rule.runOnIdle { state = state.copy(sending = true) }
        rule.onNodeWithTag("thread_sending").assertIsDisplayed()
        rule.onNodeWithTag("composer_busy").assertTextEquals("Sending…")
    }

    @Test fun losingFocusPublishesCommittedCompositionWithoutChangingText() {
        val editor = dev.avillagran.ot3android.ui.thread.ComposerEditorState("Compose")
        lateinit var focus: androidx.compose.ui.focus.FocusManager
        var committed: androidx.compose.ui.text.input.TextFieldValue? = null
        rule.setContent { Ot3Theme {
            focus = androidx.compose.ui.platform.LocalFocusManager.current
            ComposerEditor(editor, false, false, false, { _, _ -> }, {}, {}, onEditorValue = { committed = it })
        } }
        rule.onNodeWithTag("thread_composer").performClick()
        rule.runOnIdle {
            editor.edit(androidx.compose.ui.text.input.TextFieldValue("Compose", TextRange(1, 5), TextRange(0, 7)))
            committed = null
        }
        rule.runOnIdle { focus.clearFocus(force = true) }
        rule.runOnIdle {
            assertNotNull("Blur must notify the owner, not just clear the local range", committed)
            assertEquals("Compose", committed!!.text)
            assertNull(committed!!.composition)
        }
    }

    @Test fun selectionAndChipsSurviveSavedStateRestoration() {
        val session = ComposerSession(initial()); var state by mutableStateOf(session.snapshot)
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Ot3Theme {
            ComposerParity(state, emptySet(), { _, text, selection, composing -> session.edit(text, selection, composing); state = session.snapshot }, {})
        } }
        rule.onNodeWithTag("thread_composer").performTextInputSelection(TextRange(2, 7))
        rule.runOnIdle { assertEquals(ComposerSelection(2, 7), state.selection) }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("notes.md").assertIsDisplayed()
        rule.onNodeWithTag("thread_composer").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 7)))
    }
    @Test fun uncertainSendDisablesAllAlternateDispatchesAndEditing() {
        val requests = mutableListOf<ComposerRequest>()
        rule.setContent { Ot3Theme {
            ComposerParity(initial().copy(uncertainCommand = "durable-id"),
                setOf(ComposerAction.SendAndNew, ComposerAction.OppositeOnce), { _, _, _, _ -> fail("locked editor") }, requests::add)
        } }
        rule.onNodeWithTag("composer_actions").performClick()
        rule.onNodeWithText("Send and start new thread").assertIsNotEnabled()
        rule.onNodeWithText("Opposite delivery once").assertIsNotEnabled()
        rule.onNodeWithTag("thread_composer").assertIsNotEnabled()
        assertTrue(requests.isEmpty())
    }
    @Test fun failedUploadBlocksSendButCanBeRemoved() {
        val session = ComposerSession(initial().copy(attachments = listOf(ComposerAttachment("u", "notes.md", UploadStatus.Failed, error = "failed"))))
        var state by mutableStateOf(session.snapshot)
        rule.setContent { Ot3Theme {
            ComposerParity(state, emptySet(), { _, _, _, _ -> }, { request ->
                if (request is ComposerRequest.RemoveAttachment) { session.removeAttachment(request.id); state = session.snapshot }
            })
        } }
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        rule.onNodeWithText("Remove").performClick()
        rule.onNodeWithContentDescription("Send").assertIsEnabled()
    }
}
