package dev.avillagran.ot3android.ui.thread

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import org.junit.*
import org.junit.Assert.*

/** Runs only on the selected test emulator; restores display overrides even after failure. */
class PhoneThreadLayoutDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var size: String? = null
    private var density: String? = null
    private var navigation: String? = null
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }
    @Before fun xiaomiDisplay() {
        size = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        density = Regex("Override density: (\\d+)").find(shell("wm density"))?.groupValues?.get(1)
        navigation = Regex("\\[x] (com\\.android\\.internal\\.systemui\\.navbar\\.[a-z_]+)")
            .find(shell("cmd overlay list"))?.groupValues?.get(1)
        shell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton")
        shell("wm size 1220x2712")
        shell("wm density 544")
    }
    @After fun restoreDisplay() {
        navigation?.let { shell("cmd overlay enable-exclusive --category $it") }
        shell("wm size ${size ?: "reset"}")
        shell("wm density ${density ?: "reset"}")
    }
    @Test fun landscapeSplitReaderOwnsSixtyPercentAndFindCannotCoverMessages() = phone(true)
    @Test fun portraitFindCannotCoverMessagesAndActionsStayInOverflow() = phone(false)
    private fun phone(landscape: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val evidence = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: context.getExternalFilesDir(null)!!
        evidence.mkdirs()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("parity", true).putExtra("landscape", landscape)).use { scenario ->
            scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("composer_actions").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
            // Geometry describes the reading surface, before editing. Do not inherit an
            // asynchronously restored IME from a preceding editor/rotation fixture.
            scenario.onActivity {
                androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                    .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            rule.waitUntil(15_000) {
                var hidden = false
                scenario.onActivity {
                    hidden = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
                }
                hidden
            }
            var windowHeight = 0
            scenario.onActivity {
                assertEquals(3.4f, it.resources.displayMetrics.density, .001f)
                val bounds = it.windowManager.currentWindowMetrics.bounds
                assertEquals(if (landscape) 2712 else 1220, bounds.width())
                assertEquals(if (landscape) 1220 else 2712, bounds.height())
                windowHeight = bounds.height()
            }
            if (landscape) rule.waitUntil(15_000) {
                rule.onNodeWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNode().boundsInWindow.height >= windowHeight * .60f
            }
            run {
                val reader = rule.onNodeWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNode().boundsInWindow
                if (landscape) assertTrue("reader=$reader windowHeight=$windowHeight", reader.height >= windowHeight * .60f)
                java.io.File(evidence, "phone-thread-${if (landscape) "landscape" else "portrait"}.txt")
                    .writeText("density=3.4 windowHeight=$windowHeight reader=$reader readerFraction=${reader.height / windowHeight}\n")
                val find = rule.onNodeWithTag("transcript_find").fetchSemanticsNode().boundsInWindow
                val messages = rule.onAllNodes(hasTestTagPrefix("thread_message_")).fetchSemanticsNodes()
                assertTrue("No message was laid out", messages.isNotEmpty())
                messages.forEach {
                    assertFalse("Find=$find message=${it.boundsInWindow}", find.overlaps(it.boundsInWindow))
                }
            }
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(evidence, "phone-thread-${if (landscape) "landscape" else "portrait"}.png")
                .outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            rule.onNodeWithTag(ThreadScreenTags.SENDING).assertDoesNotExist()
            rule.onNodeWithTag(ThreadScreenTags.SEND).assertIsNotEnabled()
            rule.onNodeWithText("Attach files").assertDoesNotExist()
            rule.onNodeWithTag("composer_actions").performClick()
            rule.onNodeWithText("Attach files").assertIsDisplayed()
            androidx.test.espresso.Espresso.pressBack()
            rule.onNodeWithTag("transcript_find").performClick()
            rule.onNodeWithTag("transcript_query").assertIsDisplayed()
        }
    }
    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }
}
