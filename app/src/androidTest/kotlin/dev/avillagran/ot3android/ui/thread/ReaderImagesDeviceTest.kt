package dev.avillagran.ot3android.ui.thread

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual Home → thread → image; deterministic server + binary fixture, not a live asset grant. */
class ReaderImagesDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    // Each fixture uses the same synthetic authority; do not inherit another test's bytes.
    @org.junit.Before fun clearImageCache() {
        dev.avillagran.ot3android.core.thread.ReaderImageCache.shared.clearEnvironment("node")
    }
    @Test fun tokyoNight() = navigate(true)
    @Test fun latte() = navigate(false)

    private fun navigate(dark: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val theme = if (dark) "tokyo-night" else "catppuccin-latte"
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("rich", true).putExtra("dark", dark)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToNode(hasTestTag("thread_image_reader-shot_640"))
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Vista previa del lector.png").fetchSemanticsNodes().isNotEmpty() }
            capture(context, "preview.$theme")
            rule.onNodeWithTag("thread_image_reader-shot_640").performClick()
            rule.onNodeWithTag("thread_image_reader-shot_2048").assertIsDisplayed()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("thread_image_reader-shot_2048") and (hasContentDescription("Vista previa del lector.png") or hasAnyDescendant(hasContentDescription("Vista previa del lector.png")))).fetchSemanticsNodes().isNotEmpty() }
            capture(context, "fullscreen.$theme")
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitUntil(10_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            rule.onNodeWithTag("thread_image_close").assertIsDisplayed()
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            rule.waitUntil(10_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            rule.onNodeWithTag("thread_image_close").assertIsDisplayed()
            rule.onNodeWithTag("thread_image_close").performClick()
            rule.onNodeWithTag("thread_image_reader-shot_2048").assertDoesNotExist()
            scenario.onActivity { activity ->
                assertEquals("Viewer and rotation reuse the cached thumbnail bytes", 1,
                    activity.fixture.transport.log.count { it.method == "image" })
                assertTrue(activity.fixture.transport.calls.none { it.fn == "orchestration.dispatchCommand" })
            }
        }
    }

    private fun capture(context: Context, name: String) {
        rule.waitForIdle()
        // Compose idle does not include Android window enter/rotation animations.
        android.os.SystemClock.sleep(600)
        rule.waitForIdle()
        val file = java.io.File(context.getExternalFilesDir(null), "reader-images/$name.png")
        file.parentFile!!.mkdirs()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
