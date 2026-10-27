package dev.avillagran.ot3android.ui.thread

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real navigation/VM/Rust, synthetic provider; injected IME and emulated physical keyboard are distinct. */
class ExpandedComposerDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var activeScenario: ActivityScenario<WorkspaceFixtureActivity>
    @Test fun rapidEditingExpandBackKeepsTheSameDraftAndSelection() = launch(true) { scenario ->
        field().performTextReplacement("Rapid edit"); field().performTextInput(" before expanding")
        field().performTextInputSelection(TextRange(2, 8)); capture("tokyo", "compact")
        rule.onNodeWithTag("composer_expand").performClick(); selection(2, 8)
        field().performTextInput("selected"); field().performTextInputSelection(TextRange(1, 6))
        capture("tokyo", "editing"); rule.onNodeWithTag("composer_back").performClick()
        field().assertTextContains("Raselectedit before expanding"); selection(1, 6)
        capture("tokyo", "back"); assertCount(scenario, 0)
    }
    @Test fun tokyoSelectionBackRotationAndImeKeepOneThreadSession() = launch(true) { scenario ->
        field().performTextReplacement(SAMPLE); field().performTextInputSelection(TextRange(4, 12))
        rule.onNodeWithTag("composer_expand").performClick(); selection(4, 12)
        field().performClick(); field().performTextInputSelection(TextRange(4, 12))
        waitIme(scenario, true)
        assertControlsAboveIme(scenario)
        scenario.recreate(); rule.waitUntil(15_000) { rule.onAllNodesWithTag("composer_expanded").fetchSemanticsNodes().isNotEmpty() }
        selection(4, 12); field().assertTextContains(SAMPLE)
        rotate(scenario, true); field().assertTextContains(SAMPLE); selection(4, 12); assertControlsAboveIme(scenario)
        rotate(scenario, false); selection(4, 12)
        field().performTextInputSelection(TextRange(SAMPLE.length)); capture("tokyo", "expanded")
        field().performTextInputSelection(TextRange(4, 12)); rule.onNodeWithTag("composer_back").performClick()
        rule.onNodeWithTag("composer_expanded").assertDoesNotExist(); selection(4, 12); field().assertTextContains(SAMPLE)
        waitIme(scenario, false)
        scenario.onActivity {
            assertEquals(1, it.fixture.transport.collectors("chat").value)
            assertEquals(1, it.fixture.transport.log.count { call -> call.method == "boundedThread" })
            assertEquals(0, commands(it).size)
        }
    }
    @Test fun latteSoftEnterNeverSendsPhysicalKeysFollowTheExactMode() = launch(false) { scenario ->
        field().performTextReplacement(SAMPLE); field().performTextInputSelection(TextRange(SAMPLE.length))
        inject(KeyEvent.KEYCODE_ENTER, soft = true); rule.waitForIdle(); assertCount(scenario, 0)
        field().assertTextContains(SAMPLE + "\n")
        inject(KeyEvent.KEYCODE_ENTER, meta = KeyEvent.META_SHIFT_ON); assertCount(scenario, 0)
        inject(KeyEvent.KEYCODE_ENTER, repeat = 1); assertCount(scenario, 0)
        inject(KeyEvent.KEYCODE_ENTER); waitCalls(scenario, 1)
        field().performTextReplacement(SAMPLE)
        rule.onNodeWithTag("composer_expand").performClick(); field().performTextInputSelection(TextRange(SAMPLE.length))
        waitIme(scenario, true); capture("latte", "expanded")
        inject(KeyEvent.KEYCODE_ENTER); assertCount(scenario, 1) // Expanded physical Enter is newline.
        field().assertTextContains(SAMPLE + "\n")
        inject(KeyEvent.KEYCODE_ENTER, meta = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON); assertCount(scenario, 1)
        inject(KeyEvent.KEYCODE_ENTER, meta = KeyEvent.META_CTRL_ON); waitCalls(scenario, 2)
        rule.waitUntil(15_000) { text().isEmpty() }
    }
    @Test fun unknownRetryAndExpandedQueueEditPreserveOriginalDraftRangeAndAttachments() = launch(false, "queue") { scenario ->
        val original = "Keep this original draft"
        field().performTextReplacement(original); field().performTextInputSelection(TextRange(3, 9))
        rule.onNodeWithTag("thread_queue").performClick(); rule.onNodeWithTag("queue_edit_q1").performClick()
        rule.onNodeWithTag("composer_expand").performClick(); field().performTextReplacement("Revised expanded queue")
        rotate(scenario, true); field().assertTextContains("Revised expanded queue"); rotate(scenario, false)
        rule.onNodeWithText("Cancel edit").performScrollTo().performClick()
        field().assertTextContains(original); selection(3, 9)
        rule.onNodeWithTag("composer_back").performClick()
        rule.onNodeWithTag("thread_queue").performClick(); rule.onNodeWithTag("queue_edit_q1").performClick()
        rule.onNodeWithTag("composer_expand").performClick(); field().performTextReplacement("Final expanded queue")
        rule.onNodeWithTag(ThreadScreenTags.SEND).performClick(); waitCalls(scenario, 1)
        rule.waitUntil(15_000) { text() == original }; selection(3, 9)
        scenario.onActivity {
            val edit = commands(it).single(); assertEquals("queued-run.edit", edit.getString("type"))
            assertEquals("notes.txt", edit.getJSONArray("attachments").getJSONObject(0).getString("name"))
            assertEquals("/fixture/notes.txt", edit.getJSONObject("context").getJSONArray("filePaths").getString(0))
        }
    }
    @Test fun unknownResponseIsReachableInExpandedSurfaceAndRetriesSameBytes() = launch(true) { scenario ->
        scenario.onActivity { it.fixture.queueUnknownFirst = true }
        field().performTextReplacement("Unknown expanded send")
        rule.onNodeWithTag("composer_expand").performClick(); rule.onNodeWithTag(ThreadScreenTags.SEND).performClick(); waitCalls(scenario, 1)
        var id = ""; var payload = ""
        scenario.onActivity { val command = commands(it).single(); id = command.getString("commandId"); payload = command.toString() }
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.resend(id)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(ThreadScreenTags.resend(id)).performScrollTo().assertIsDisplayed().performClick(); waitCalls(scenario, 2)
        scenario.onActivity { assertEquals(payload, commands(it)[1].toString()) }
    }
    private fun launch(dark: Boolean, request: String = "editor", body: (ActivityScenario<WorkspaceFixtureActivity>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java).putExtra("dark", dark).putExtra("request", request)).use { scenario ->
            activeScenario = scenario
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.COMPOSER).fetchSemanticsNodes().isNotEmpty() }
            body(scenario)
        }
    }
    private fun field() = rule.onNodeWithTag(ThreadScreenTags.COMPOSER)
    private fun text() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun selection(start: Int, end: Int) { rule.waitUntil(15_000) { field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange] == TextRange(start, end) } }
    private fun commands(activity: WorkspaceFixtureActivity) = activity.fixture.transport.calls.filter { it.fn == "orchestration.dispatchCommand" }.map { it.args }
    private fun assertCount(scenario: ActivityScenario<WorkspaceFixtureActivity>, count: Int) { scenario.onActivity { assertEquals(count, commands(it).size) } }
    private fun waitCalls(scenario: ActivityScenario<WorkspaceFixtureActivity>, count: Int) { var size = 0; rule.waitUntil(15_000) { scenario.onActivity { size = commands(it).size }; size == count } }
    private fun inject(key: Int, meta: Int = 0, soft: Boolean = false, repeat: Int = 0) {
        val physical = InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull().firstOrNull { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_KEYBOARD) }
        if (!soft) assertNotNull("Emulated physical keyboard required; SOURCE_KEYBOARD alone is insufficient", physical)
        val now = SystemClock.uptimeMillis()
        val device = if (soft) KeyCharacterMap.VIRTUAL_KEYBOARD else physical!!.id
        val flags = if (soft) KeyEvent.FLAG_SOFT_KEYBOARD else 0
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, repeat, meta, device, 0, flags, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent(now, now + 1, KeyEvent.ACTION_UP, key, 0, meta, device, 0, flags, InputDevice.SOURCE_KEYBOARD)
        if (soft) { assertTrue(automation.injectInputEvent(down, true)); assertTrue(automation.injectInputEvent(up, true)) }
        else activeScenario.onActivity { it.dispatchKeyEvent(down); it.dispatchKeyEvent(up) }
        rule.waitForIdle()
    }
    private fun rotate(scenario: ActivityScenario<WorkspaceFixtureActivity>, landscape: Boolean) {
        scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitUntil(15_000) { rule.onNodeWithTag(ThreadScreenTags.ROOT).fetchSemanticsNode().boundsInRoot.let { if (landscape) it.width > it.height else it.height > it.width } }
    }
    private fun waitIme(scenario: ActivityScenario<WorkspaceFixtureActivity>, expected: Boolean) {
        var visible = !expected
        rule.waitUntil(15_000) { scenario.onActivity { visible = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }; visible == expected }
    }
    private fun assertControlsAboveIme(scenario: ActivityScenario<WorkspaceFixtureActivity>) {
        val send = rule.onNodeWithTag(ThreadScreenTags.SEND).fetchSemanticsNode().boundsInRoot
        val back = rule.onNodeWithTag("composer_back").fetchSemanticsNode().boundsInRoot
        val editor = field().fetchSemanticsNode().boundsInRoot
        scenario.onActivity {
            val ime = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)!!.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val bottom = it.window.decorView.height - ime
            assertTrue(send.bottom <= bottom); assertTrue(back.bottom <= bottom); assertTrue(editor.bottom <= bottom)
            assertTrue("Readable editor viewport", editor.height >= 40f)
        }
    }
    private fun capture(theme: String, name: String) {
        if (InstrumentationRegistry.getArguments().getString("editorEvidence") != "true") return
        rule.waitForIdle(); Thread.sleep(600)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "editor-$theme-$name.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    companion object { private const val SAMPLE = "Expanded composer\nKeep one draft and selection.\nReview the notes before sending." }
}
