package dev.avillagran.ot3android.ui.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Touch actions and real Android clipboard URI readback; synthetic app fixture only. */
class ReaderImageCopyDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    // Each fixture uses the same synthetic authority; do not inherit another test's bytes.
    @org.junit.Before fun clearImageCache() {
        dev.avillagran.ot3android.core.thread.ReaderImageCache.shared.clearEnvironment("node")
    }
    @Test fun tokyoNight() = copy(true)
    @Test fun latte() = copy(false)

    private fun copy(dark: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val theme = if (dark) "tokyo-night" else "catppuccin-latte"
        val expected = context.assets.open("reader/preview.png").use { it.readBytes() }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("rich", true).putExtra("dark", dark)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { clipboard.setPrimaryClip(ClipData.newPlainText("ot3 test fixture", "unchanged")) }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_image_reader-shot_640"))
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Vista previa del lector.png").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { assertEquals("unchanged", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
            rule.onNodeWithTag("thread_image_copy_thumbnail").assertDoesNotExist()
            rule.onNodeWithTag("thread_image_reader-shot_640").performTouchInput { longClick() }
            rule.onNodeWithTag("thread_image_copy_menu").assertIsDisplayed()
            capture(context, "thumbnail.$theme")

            // Force a cache miss so the deferred transport exercises the busy action.
            dev.avillagran.ot3android.core.thread.ReaderImageCache.shared.clearEnvironment("node")
            val release = CompletableDeferred<Unit>()
            scenario.onActivity { it.fixture.transport.onImage = { _, _, _ -> release.await(); expected } }
            rule.onNodeWithTag("thread_image_copy_menu").performClick()
            rule.onNodeWithTag("thread_image_copy_menu").assertIsNotEnabled()
            capture(context, "busy.$theme")
            scenario.onActivity { assertEquals("unchanged", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
            release.complete(Unit)
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Copied!").fetchSemanticsNodes().isNotEmpty() }
            var firstUri = ""
            scenario.onActivity {
                val clip = clipboard.primaryClip!!
                assertTrue(clip.description.hasMimeType("image/png"))
                val uri = requireNotNull(clip.getItemAt(0).uri)
                firstUri = uri.toString()
                assertEquals("content", uri.scheme)
                assertEquals("image/png", it.contentResolver.getType(uri))
                assertArrayEquals(expected, it.contentResolver.openInputStream(uri)!!.use { input -> input.readBytes() })
            }
            capture(context, "copied.$theme")
            // Force another miss: a cached copy deliberately needs no transport.
            dev.avillagran.ot3android.core.thread.ReaderImageCache.shared.clearEnvironment("node")
            // A failed explicit action retains the previous valid clipboard.
            scenario.onActivity { it.fixture.transport.onImage = { _, _, _ -> error("synthetic_copy_failure") } }
            rule.onNodeWithTag("thread_image_copy_menu").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Copy failed").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { assertEquals(firstUri, clipboard.primaryClip!!.getItemAt(0).uri.toString()) }
            capture(context, "error.$theme")
            scenario.onActivity { it.fixture.transport.onImage = { _, _, _ -> expected } }
            androidx.test.espresso.Espresso.pressBack()
            // TalkBack exposes the same menu without a long-press gesture.
            val actions = rule.onNodeWithTag("thread_image_reader-shot_640").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
            rule.runOnIdle { assertTrue(actions.single().action()) }
            rule.onNodeWithTag("thread_image_copy_menu").assertIsDisplayed()
            androidx.test.espresso.Espresso.pressBack()
            rule.onNodeWithTag("thread_image_reader-shot_640").performClick()
            rule.onNodeWithTag("thread_image_copy_viewer").assertIsDisplayed()
            scenario.onActivity { assertEquals(firstUri, clipboard.primaryClip!!.getItemAt(0).uri.toString()) }
            rule.onNodeWithTag("thread_image_copy_viewer").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Copied!").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity {
                val uri = clipboard.primaryClip!!.getItemAt(0).uri!!
                assertNotEquals(firstUri, uri.toString())
                assertArrayEquals(expected, it.contentResolver.openInputStream(uri)!!.use { input -> input.readBytes() })
                assertTrue(it.fixture.transport.calls.none { call -> call.fn == "orchestration.dispatchCommand" })
            }
            capture(context, "viewer.$theme")
            rule.onNodeWithTag("thread_image_close").performClick()
        }
    }

    private fun capture(context: Context, name: String) {
        rule.waitForIdle()
        android.os.SystemClock.sleep(250)
        val file = java.io.File(context.getExternalFilesDir(null), "reader-image-copy/$name.png")
        file.parentFile!!.mkdirs()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
