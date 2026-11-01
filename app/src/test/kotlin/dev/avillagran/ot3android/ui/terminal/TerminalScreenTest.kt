package dev.avillagran.ot3android.ui.terminal

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.terminal.TerminalEmulator
import dev.avillagran.ot3android.core.terminal.TerminalSession
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class TerminalScreenTest {
    @get:Rule val rule = createComposeRule()
    private val sent = mutableListOf<String>()
    private fun surface(operate: Boolean = true, command: String? = null) {
        rule.setContent { Ot3Theme { TerminalSurface("host-a", "thread-a", TerminalSession.View(TerminalEmulator().frame(), "/remote/worktree", true, operate), command,
            sent::add, sent::add, {}, { _, _ -> }, onClose = {}) } }
    }
    @Test fun readOnlyHasOutputButNoInputOrExecution() {
        surface(false)
        rule.onNodeWithTag("terminal_destination").assertTextEquals("host-a · /remote/worktree")
        rule.onNodeWithText("Read-only terminal").assertExists()
        rule.onNodeWithTag("terminal_run").assertIsNotEnabled()
        rule.onNodeWithTag("terminal_paste").assertIsNotEnabled()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertTrue(sent.isEmpty())
    }
    @Test fun runCodeRequiresExplicitConfirmationAndConsumesItOnce() {
        surface(command = "printf 東京")
        assertTrue(sent.isEmpty())
        rule.onAllNodesWithText("host-a · /remote/worktree").assertCountEquals(2)
        rule.onNodeWithTag("terminal_confirm").performClick()
        rule.runOnIdle { assertEquals(listOf("printf 東京\r"), sent) }
        rule.onNodeWithTag("terminal_confirm").assertDoesNotExist()
    }
    @Test fun cancelledCommandSendsNothing() {
        surface(command = "rm file")
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertTrue(sent.isEmpty()) }
    }
    @Test fun imeCompositionIsCommittedOnceAndDeleteIsBackspace() {
        val view = TerminalView(ApplicationProvider.getApplicationContext<Context>())
        view.writable = true; view.send = sent::add
        val input = view.onCreateInputConnection(EditorInfo())!!
        input.setComposingText("に", 1); input.setComposingText("日本", 1)
        assertTrue(sent.isEmpty())
        input.commitText("日本", 1); input.finishComposingText()
        input.deleteSurroundingText(1, 0)
        assertEquals(listOf("日本", "\u007f"), sent)
        view.writable = false
        assertNull(view.onCreateInputConnection(EditorInfo()))
        input.commitText("blocked", 1); assertEquals(2, sent.size)
    }
    private fun fakeIme(): android.view.inputmethod.InputConnection {
        val view = TerminalView(ApplicationProvider.getApplicationContext<Context>())
        view.writable = true
        view.send = sent::add
        return view.onCreateInputConnection(EditorInfo())!!
    }
    @Test fun rawEditorDisablesPredictionAndCapitalization() {
        val view = TerminalView(ApplicationProvider.getApplicationContext<Context>())
        view.writable = true
        val info = EditorInfo()
        view.onCreateInputConnection(info)
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            info.inputType and android.text.InputType.TYPE_MASK_VARIATION)
        assertEquals(0, info.inputType and (android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS))
        assertTrue(info.inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
    }
    @Test fun fakeImeRecomposesAndCommitsEachCharacterExactlyOnce() {
        val ime = fakeIme()
        ime.beginBatchEdit()
        ime.setComposingText("e", 1)
        ime.setComposingText("ech", 1)
        ime.setComposingRegion(0, 3)
        ime.setComposingText("echo", 1)
        assertEquals("echo", ime.getTextBeforeCursor(100, 0).toString())
        assertTrue(sent.isEmpty())
        ime.commitText("echo", 1)
        ime.finishComposingText()
        ime.endBatchEdit()
        ime.commitText(" o3-terminal-ok", 1)
        ime.performEditorAction(EditorInfo.IME_ACTION_UNSPECIFIED)
        assertEquals("echo o3-terminal-ok\r", sent.joinToString(""))
    }
    @Test fun fakeImeDeletesAndReplacesDraftWithoutSendingItTwice() {
        val ime = fakeIme()
        ime.setComposingText("o3-o3", 1)
        ime.deleteSurroundingText(3, 0)
        assertEquals("o3", ime.getTextBeforeCursor(100, 0).toString())
        ime.setComposingText("o3-terminal-ok", 1)
        ime.finishComposingText()
        ime.finishComposingText()
        ime.deleteSurroundingText(1, 1)
        assertEquals(listOf("o3-terminal-ok", "\u007f\u001b[3~"), sent)
    }
    @Test fun fakeImeCodePointDeleteKeepsSurrogatesIntact() {
        val ime = fakeIme()
        ime.setComposingText("a😀", 1)
        ime.deleteSurroundingTextInCodePoints(1, 0)
        ime.commitText("a", 1)
        assertEquals(listOf("a"), sent)
    }
    @Test fun fakeImePasteNormalizesNewlinesAndAdbStyleKeysPreserveCase() {
        val ime = fakeIme()
        ime.commitText("printf a\r\nprintf b\n", 1)
        val events = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents("echo o3-terminal-ok".toCharArray())!!
        events.forEach { ime.sendKeyEvent(it) }
        ime.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        ime.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        assertEquals("printf a\rprintf b\recho o3-terminal-ok\r", sent.joinToString(""))
    }
    @Test fun layoutReportsImeAndRotationDimensions() {
        val view = TerminalView(ApplicationProvider.getApplicationContext<Context>())
        val sizes = mutableListOf<Pair<Int, Int>>()
        view.dimensions = { c, r -> sizes += c to r }
        view.layout(0, 0, 800, 1200); view.layout(0, 0, 800, 500); view.layout(0, 0, 1200, 400)
        assertEquals(3, sizes.size)
        assertEquals(sizes[0].first, sizes[1].first)
        assertTrue(sizes[1].second < sizes[0].second)
        assertTrue(sizes[2].first > sizes[1].first)
    }
    @Test fun pasteConfirmationShowsAndSendsTheWholeBoundedTextOnce() {
        surface()
        val text = "a".repeat(4100) + "; echo tail"
        rule.runOnIdle {
            val context = ApplicationProvider.getApplicationContext<Context>()
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("fixture", text))
        }
        rule.onNodeWithTag("terminal_paste").performClick()
        rule.onNodeWithText(text).assertExists()
        rule.runOnIdle { assertTrue(sent.isEmpty()) }
        rule.onAllNodesWithText("Paste").onLast().performClick()
        rule.runOnIdle { assertEquals(listOf(text), sent) }
    }
    @Test fun oversizedPasteIsRejectedWithoutTruncatingOrSendingIt() {
        surface()
        rule.runOnIdle {
            val context = ApplicationProvider.getApplicationContext<Context>()
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("fixture", "x".repeat(65537)))
        }
        rule.onNodeWithTag("terminal_paste").performClick()
        rule.onNodeWithText("Input is too large. Send less than 64 KiB at a time.").assertExists()
        rule.runOnIdle { assertTrue(sent.isEmpty()) }
    }
    @Test fun rapidRepeatedConfirmationCallbackCannotDuplicateACommand() {
        surface(command = "echo once")
        val confirm = rule.onNodeWithTag("terminal_confirm").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        rule.runOnIdle { confirm(); confirm() }
        rule.runOnIdle { assertEquals(listOf("echo once\r"), sent) }
    }
}
