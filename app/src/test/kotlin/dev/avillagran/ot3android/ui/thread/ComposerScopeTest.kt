package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.thread.ComposerState
import dev.avillagran.ot3android.core.thread.ThreadView
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ComposerScopeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun shortWindowKeepsToolbarControlsInTheSingleRow() {
        rule.setContent { Ot3Theme {
            ComposerEditor(ComposerEditorState(), expanded = false, canSend = false, sending = false,
                onDraftChange = { _, _ -> }, onSend = {}, onToggle = {}, compact = true,
                toolbar = { androidx.compose.material3.Text("model", androidx.compose.ui.Modifier.testTag("model_control")) })
        } }
        rule.onNodeWithTag("model_control").assertIsDisplayed()
        rule.onNodeWithTag("composer_control_row").assertDoesNotExist()
    }
    @Test fun selectionSurvivesModeAndRestoreButNotAnotherEnvironmentWithSameThreadId() {
        var identity by mutableStateOf("[node,same-thread]")
        var draft by mutableStateOf("Original normal draft")
        var token by mutableStateOf<String?>(null)
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Ot3Theme {
            ThreadContent(ThreadUiState(ThreadView("same-thread", loaded = true), composer = ComposerState(draft = draft, draftToken = token)),
                { draft = it }, {}, {}, {}, {}, {}, {}, {}, editorIdentity = identity,
                onDraftChangeTagged = { text, echo -> draft = text; token = echo })
        } }
        field().performTextInputSelection(TextRange(2, 7))
        rule.onNodeWithTag("composer_expand").performClick(); assertSelection(2, 7)
        restoration.emulateSavedInstanceStateRestore(); assertSelection(2, 7)
        rule.onNodeWithTag("composer_back").performClick(); assertSelection(2, 7)
        rule.runOnIdle { draft = "External draft"; token = null }; field().assertTextContains("External draft"); assertSelection(2, 7)
        rule.runOnIdle { identity = "[vq,same-thread]"; draft = "Other environment"; token = null }
        field().assertTextContains("Other environment"); assertSelection(draft.length, draft.length)
        rule.onNodeWithTag("composer_expanded").assertDoesNotExist()
    }
    @Test fun normalSelectionAndDraftSurviveTemporaryQueueEditor() {
        var queue by mutableStateOf<String?>(null)
        val original = "Original normal draft"
        rule.setContent { Ot3Theme {
            ThreadContent(ThreadUiState(ThreadView("chat", loaded = true), composer = ComposerState(draft = original), queueEdit = queue),
                {}, {}, {}, {}, {}, {}, {}, {}, onCancelQueueEdit = { queue = null })
        } }
        field().performTextInputSelection(TextRange(1, 6))
        rule.runOnIdle { queue = JSONObject().put("runId", "q1").put("text", "Queue text").toString() }
        field().assertTextContains("Queue text"); field().performTextInputSelection(TextRange(3))
        rule.onNodeWithTag("composer_expand").performClick()
        rule.onNodeWithText("Cancel edit").performClick()
        field().assertTextContains(original); assertSelection(1, 6)
    }
    @Test fun durableComposerSelectionSurvivesExpandedQueueCancellationAndRecreation() {
        var queue by mutableStateOf<String?>(null)
        val original = "Original normal draft"
        val scope = dev.avillagran.ot3android.core.composer.ComposerScope("node", "project", "chat")
        var snapshot by mutableStateOf(dev.avillagran.ot3android.core.composer.ComposerSnapshot(
            scope, text = original,
            selection = dev.avillagran.ot3android.core.composer.ComposerSelection(original.length, original.length)))
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Ot3Theme {
            ThreadContent(ThreadUiState(ThreadView("chat", loaded = true), composer = ComposerState(draft = original), queueEdit = queue),
                { text -> queue = queue?.let { JSONObject(it).put("text", text).toString() } },
                {}, {}, {}, {}, {}, {}, {}, onCancelQueueEdit = { queue = null },
                composerSlot = { controls ->
                    dev.avillagran.ot3android.ui.thread.composer.ComposerParity(snapshot, emptySet(),
                        onEdit = { _, text, selection, composing -> snapshot = snapshot.copy(text = text, selection = selection, composing = composing) },
                        onRequest = {}, toolbar = controls)
                })
        } }
        field().performTextInputSelection(TextRange(3, 9))
        rule.runOnIdle { queue = JSONObject().put("runId", "q1").put("text", "Queue text").toString() }
        rule.onNodeWithTag("composer_expand").performClick()
        field().performTextReplacement("Revised queue")
        restoration.emulateSavedInstanceStateRestore()
        field().assertTextContains("Revised queue")
        rule.onNodeWithText("Cancel edit").performClick()
        field().assertTextContains(original); assertSelection(3, 9)
        rule.onNodeWithTag("composer_back").performClick()
        field().assertTextContains(original); assertSelection(3, 9)
    }
    private fun field() = rule.onNodeWithTag(ThreadScreenTags.COMPOSER)
    private fun assertSelection(start: Int, end: Int) { assertEquals(TextRange(start, end), field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]) }
}
