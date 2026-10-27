package dev.avillagran.ot3android.ui.thread

import android.view.KeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class ComposerEditorStateTest {
    private fun enter(expanded: Boolean = false, shift: Boolean = false, ctrl: Boolean = false,
        soft: Boolean = false, virtual: Boolean = false, repeat: Int = 0, composing: Boolean = false) = composerKeyAction(
        KeyEvent.KEYCODE_ENTER, true, repeat, soft, virtual, true, shift, ctrl, false, false, composing, expanded)
    @Test fun keyboardSourceAloneCannotTurnImeEnterIntoSend() {
        assertEquals(ComposerKeyAction.Platform, enter(soft = true))
        assertEquals(ComposerKeyAction.Platform, enter(virtual = true))
        assertEquals(ComposerKeyAction.Platform, enter(soft = true, virtual = true))
        assertEquals(ComposerKeyAction.Send, enter())
        assertEquals(ComposerKeyAction.Newline, enter(shift = true))
    }
    @Test fun expandedEnterAndShiftEnterAreNewlineCtrlEnterIsExistingSendOnly() {
        assertEquals(ComposerKeyAction.Newline, enter(expanded = true))
        assertEquals(ComposerKeyAction.Newline, enter(expanded = true, shift = true))
        assertEquals(ComposerKeyAction.Send, enter(expanded = true, ctrl = true))
        assertEquals(ComposerKeyAction.Consume, enter(ctrl = true))
        assertEquals(ComposerKeyAction.Consume, enter(expanded = true, shift = true, ctrl = true))
    }
    @Test fun repeatsAndComposedCharactersNeverSendOrInsertOurNewline() {
        assertEquals(ComposerKeyAction.Consume, enter(repeat = 1))
        assertEquals(ComposerKeyAction.Consume, enter(expanded = true, repeat = 1, ctrl = true))
        assertEquals(ComposerKeyAction.Platform, enter(composing = true))
        assertEquals(ComposerKeyAction.Platform, enter(expanded = true, ctrl = true, composing = true))
    }
    @Test fun externalDraftIsAuthoritativeAndSelectionClampsWithoutLosingImeOnEcho() {
        val state = ComposerEditorState("abcdef")
        state.edit(TextFieldValue("abcdef", TextRange(2, 5), TextRange(1, 5)))
        state.synchronize("abcdef")
        assertEquals(TextRange(1, 5), state.value.composition)
        state.synchronize("xy")
        assertEquals("xy", state.value.text); assertEquals(TextRange(2), state.value.selection); assertNull(state.value.composition)
        state.synchronize("")
        assertEquals(TextRange.Zero, state.value.selection)
    }
    @Test fun olderTaggedEchoCannotReplaceRapidTypingButExternalEmptyCan() {
        val state = ComposerEditorState("")
        val first = state.edit(TextFieldValue("a", TextRange(1)))!!
        val second = state.edit(TextFieldValue("abc", TextRange(3)))!!
        state.synchronize("a", first)
        assertEquals("abc", state.value.text); assertEquals(TextRange(3), state.value.selection)
        state.synchronize("abc", second)
        state.submitted(); state.synchronize("", null)
        assertEquals("", state.value.text)
        val next = state.edit(TextFieldValue("new", TextRange(2)))!!
        state.synchronize("abc", first); assertEquals("new", state.value.text)
        state.synchronize("a", null) // Legitimate external content, even if it equals a past edit.
        assertEquals("a", state.value.text)
    }
    @Test fun normalSelectionSurvivesQueueEditAndAuthoritativeCancelRestoration() {
        val editors = ComposerEditors()
        val normal = editors.editor(null, "Original draft")
        normal.edit(TextFieldValue("Original draft", TextRange(2, 7)))
        val queued = editors.editor("q1", "Queued draft")
        queued.edit(TextFieldValue("Revised queue", TextRange(4)))
        val restored = editors.editor(null, "Original draft")
        restored.synchronize("Original draft", null)
        assertTrue(restored === normal); assertEquals(TextRange(2, 7), restored.value.selection)
    }
    @Test fun pausedCompositionCanFinishWithoutCollapsingSelection() {
        val state = ComposerEditorState("Compose")
        state.edit(TextFieldValue("Compose", TextRange(1, 5), TextRange(0, 7)))
        state.finishComposition()
        assertEquals(TextRange(1, 5), state.value.selection); assertNull(state.value.composition)
    }
    @Test fun restoredSelectionWaitsForItsOwnDraftAndDoesNotSaveAnotherText() {
        val state = ComposerEditorState.StateSaver.restore(listOf(2, 5, true))!!
        assertEquals("", state.value.text)
        state.synchronize(""); state.edit(TextFieldValue("", TextRange.Zero)); state.synchronize("Restored draft")
        assertEquals(TextRange(2, 5), state.value.selection); assertTrue(state.focusWanted)
        val other = ComposerEditorState("Different thread")
        assertEquals(TextRange("Different thread".length), other.value.selection)
    }
}
