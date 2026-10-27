package dev.avillagran.ot3android.ui.thread

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real Home/thread/native projection + synthetic binary transport. No live assets or phone. */
class ReaderImageZoomDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun tokyoTouchPinchPanDoubleTapCopyClose() = gestures(true)
    @Test fun latteTouchPinchPanDoubleTapCopyClose() = gestures(false)
    @Test fun rotateAndRecreatePreserveScopedNormalizedView() = launch(true) { scenario ->
        open(); rule.onNodeWithTag("thread_image_zoom_in").performClick()
        viewport().performTouchInput { swipe(center, center + Offset(width * 0.1f, 0f)) }
        val fraction = horizontalFraction()
        scenario.recreate(); loaded(); assertEquals(200, scale()); assertEquals(fraction, horizontalFraction(), 0.02f)
        rotate(scenario, true); loaded(); assertEquals(200, scale()); bounded()
        rotate(scenario, false); loaded(); assertEquals(200, scale()); bounded()
        rule.onNodeWithTag("thread_image_close").performClick()
    }
    @Test fun failedReadCanRetryBeforeZoomWithoutDispatch() = launch(true) { scenario ->
        scenario.onActivity { it.fixture.transport.onImage = { _, _, _ -> error("synthetic_image_failure") } }
        rule.onNodeWithTag("thread_image_reader-shot_640").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("thread_image_zoom_in").assertIsNotEnabled()
        scenario.onActivity { activity -> val bytes = activity.assets.open("reader/preview.png").use { it.readBytes() }; activity.fixture.transport.onImage = { _, _, _ -> bytes } }
        rule.onNodeWithText("Retry").performClick(); loaded()
        rule.onNodeWithTag("thread_image_zoom_in").performClick(); assertEquals(200, scale())
        scenario.onActivity { assertTrue(it.fixture.transport.calls.none { c -> c.fn == "orchestration.dispatchCommand" }) }
    }
    private fun gestures(dark: Boolean) = launch(dark) { scenario ->
        open(); capture(if (dark) "tokyo-fit" else "latte-fit")
        var reads = 0
        scenario.onActivity { reads = it.fixture.transport.log.count { call -> call.method == "image" } }
        // Establish a recognized pinch first; Compose intentionally discards pre-slop zoom.
        viewport().performTouchInput {
            val x = width * 0.12f
            down(0, center - Offset(x, 0f)); down(1, center + Offset(x, 0f))
            for (step in 1..6) {
                val delta = x * (1f + step / 12f)
                updatePointerTo(0, center - Offset(delta, 0f)); updatePointerTo(1, center + Offset(delta, 0f)); move(16)
            }
        }
        val recognizedScale = scale()
        assertTrue("Pinch recognized after touch slop: $recognizedScale%", recognizedScale > 100)
        viewport().performTouchInput {
            val start = width * 0.18f
            for (step in 1..12) {
                val delta = start * (1f + step / 12f)
                updatePointerTo(0, center - Offset(delta, 0f)); updatePointerTo(1, center + Offset(delta, 0f)); move(16)
            }
            up(0); up(1)
        }
        assertEquals("Recognized two-finger distance doubled", recognizedScale * 2f, scale().toFloat(), 2f)
        val beforePan = horizontalFraction()
        viewport().performTouchInput { swipe(center, center + Offset(width * 0.2f, 0f)) }
        assertTrue("Single-finger drag pans the zoomed image", kotlin.math.abs(horizontalFraction() - beforePan) > 0.05f)
        bounded(); capture(if (dark) "tokyo-zoom" else "latte-zoom")
        viewport().performTouchInput { doubleClick(center) }; assertEquals(100, scale())
        viewport().performTouchInput { doubleClick(center) }; assertEquals(200, scale())
        rule.onNodeWithTag("thread_image_zoom_reset").performClick(); assertEquals(100, scale())
        scenario.onActivity { assertEquals("Gestures reuse decoded image", reads, it.fixture.transport.log.count { call -> call.method == "image" }) }
        rule.onNodeWithTag("thread_image_copy_viewer").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Copied!").fetchSemanticsNodes().isNotEmpty() }
        scenario.onActivity {
            val clip = it.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!
            assertTrue(clip.description.hasMimeType("image/png"))
            assertArrayEquals(it.assets.open("reader/preview.png").use { f -> f.readBytes() }, it.contentResolver.openInputStream(clip.getItemAt(0).uri!!)!!.use { f -> f.readBytes() })
            assertTrue(it.fixture.transport.calls.none { call -> call.fn == "orchestration.dispatchCommand" })
        }
        rule.onNodeWithTag("thread_image_close").performClick(); viewport().assertDoesNotExist()
        open(); assertEquals(100, scale()); rule.onNodeWithTag("thread_image_close").performClick()
    }
    private fun launch(dark: Boolean, body: (ActivityScenario<WorkspaceFixtureActivity>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java).putExtra("rich", true).putExtra("dark", dark)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_image_reader-shot_640"))
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Vista previa del lector.png").fetchSemanticsNodes().isNotEmpty() }
            body(scenario)
        }
    }
    private fun viewport() = rule.onNodeWithTag("thread_image_zoom_viewport")
    private fun loaded() { rule.waitUntil(10_000) { rule.onAllNodesWithTag("thread_image_zoom_viewport").fetchSemanticsNodes().isNotEmpty() }; viewport().assertIsDisplayed() }
    private fun open() { rule.onNodeWithTag("thread_image_reader-shot_640").performClick(); loaded() }
    private fun scale() = viewport().fetchSemanticsNode().config[SemanticsProperties.StateDescription].removeSuffix("%").toInt()
    private fun horizontalFraction(): Float { val range = viewport().fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]; return if (range.maxValue() == 0f) 0.5f else range.value() / range.maxValue() }
    private fun bounded() { val node = viewport().fetchSemanticsNode(); for (key in listOf(SemanticsProperties.HorizontalScrollAxisRange, SemanticsProperties.VerticalScrollAxisRange)) { val range = node.config[key]; assertTrue(range.value() >= -0.01f); assertTrue(range.value() <= range.maxValue() + 0.01f) } }
    private fun rotate(scenario: ActivityScenario<WorkspaceFixtureActivity>, landscape: Boolean) {
        scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        var ready = false
        rule.waitUntil(10_000) { scenario.onActivity { ready = it.window.decorView.let { view -> if (landscape) view.width > view.height else view.height > view.width } }; ready }
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("imageZoomEvidence") != "true") return
        rule.waitForIdle(); android.os.SystemClock.sleep(600)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "image-zoom/$name.png").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
