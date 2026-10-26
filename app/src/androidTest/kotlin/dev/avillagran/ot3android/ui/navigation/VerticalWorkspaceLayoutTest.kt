package dev.avillagran.ot3android.ui.navigation

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import dev.avillagran.ot3android.ui.thread.ThreadScreenTags
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VerticalWorkspaceLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<WorkspaceFixtureActivity>()
    @Test fun headerHasNoDoubleSafeInsetAndChatHasNoResidualFooter() {
        rule.waitUntil(15000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
        val density = rule.activity.resources.displayMetrics.density
        val sky = rule.onNodeWithTag("home_sky").fetchSemanticsNode().boundsInRoot
        val brand = rule.onNodeWithTag("home_brand").fetchSemanticsNode().boundsInRoot
        val list = rule.onNodeWithTag(HomeTags.LIST).fetchSemanticsNode().boundsInRoot
        assertTrue("Only one status inset above brand: $brand", brand.top - sky.top <= (androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)!!
            .getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top + density))
        assertEquals("No reserved gap below header", sky.bottom, list.top, density)
        android.util.Log.i("UI1iMeasures", "density=$density sky=$sky brand=$brand list=$list")
        rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
        rule.waitUntil(15000) { rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(NavTags.BAR).assertDoesNotExist()
        rule.onNodeWithTag("home_sky").assertDoesNotExist()
        val timeline = rule.onNodeWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNode().boundsInRoot
        val composer = rule.onNodeWithTag("thread_composer_surface").fetchSemanticsNode().boundsInRoot
        assertEquals("Timeline meets composer without footer reservation", timeline.bottom, composer.top, density)
        // Request the real IME: its inset must reduce the timeline, not become an empty footer.
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performClick().performTextInput("IME layout check")
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertIsFocused()
        // Semantics text input does not guarantee that the platform accepted the initial
        // keyboard request. CI logcat showed PHASE_CLIENT_REQUEST_IME_SHOW before the
        // window/editor was served. Request the real IME after focus and an idle frame.
        rule.waitUntil(15000) { rule.activity.hasWindowFocus() }
        rule.runOnIdle {
            androidx.core.view.WindowCompat.getInsetsController(
                rule.activity.window, rule.activity.window.decorView,
            ).show(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        rule.waitUntil(5000) { androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)!!
            .isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        rule.waitForIdle()
        // displayMetrics may shrink during adjustResize while semantics are still from the
        // previous IME animation frame. Compare against the real keyboard top after layout.
        fun keyboardTop(): Int {
            val full = rule.activity.windowManager.currentWindowMetrics.bounds.height()
            val ime = androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)!!
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            return full - ime
        }
        rule.waitUntil(5_000) {
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).fetchSemanticsNode().boundsInRoot.bottom <= keyboardTop() + density
        }
        val edited = rule.onNodeWithTag(ThreadScreenTags.COMPOSER).fetchSemanticsNode().boundsInRoot
        assertTrue("Composer remains above the real IME", edited.bottom <= keyboardTop() + density)
        val compactTimeline = rule.onNodeWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNode().boundsInRoot
        val compactComposer = rule.onNodeWithTag("thread_composer_surface").fetchSemanticsNode().boundsInRoot
        assertEquals("IME creates no gap", compactTimeline.bottom, compactComposer.top, density)
        assertTrue("Real IME reduces usable timeline", compactTimeline.height < timeline.height - 150 * density)
        android.util.Log.i("UI1iMeasures", "timeline=$timeline composer=$composer imeTimeline=$compactTimeline imeComposer=$compactComposer")
    }
    @Test fun mainActivityIsTheOnlyLauncher() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entries = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
            0)
        assertEquals(listOf("dev.avillagran.ot3android.MainActivity"), entries.map { it.activityInfo.name })
    }
}
