package dev.avillagran.ot3android.ui.thread

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real graph, view model, native scope/request/parse; deterministic transport, not live T3. */
class ToolInspectorDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun tokyoInspector() = exercise(true)
    @Test fun latteInspector() = exercise(false)

    private fun exercise(dark: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = if (dark) "tokyo" else "latte"
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("rich", true).putExtra("dark", dark)).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
            val timeline = rule.onNodeWithTag(ThreadScreenTags.TIMELINE)
            timeline.performScrollToNode(hasTestTag("thread_fold_work/think-reader"))
            rule.onNodeWithTag("thread_fold_work/think-reader").performClick()
            scenario.onActivity { assertTrue(it.fixture.transport.calls.none { call -> call.fn == "orchestration.getTurnItem" }) }
            timeline.performScrollToNode(hasTestTag(ThreadScreenTags.message("work/think-reader")))
            // Group entries have their own bounded scroll surface. Reveal the target
            // through every scroll ancestor before injecting a real touch.
            rule.onNodeWithTag("thread_inspect_work/think-reader_0").performScrollTo().assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("thread_copy_inspector_reasoning").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("thread_inspector_tab_details").performScrollTo()
            capture("$name-details-portrait")
            rule.onNodeWithTag("thread_inspector_tab_metadata").performClick()
            rule.onNodeWithTag("thread_inspector_section_metadata").assertExists()
            rule.onNodeWithTag("thread_inspector_tab_raw").performScrollTo()
            rule.onNodeWithTag("thread_inspector_tab_raw").performClick()
            rule.onNodeWithTag("thread_inspector_section_raw").assertTextContains("think-reader", substring = true)
            capture("$name-raw-portrait")
            scenario.onActivity { assertEquals(1, it.fixture.transport.calls.count { call -> call.fn == "orchestration.getTurnItem" }) }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("thread_inspector_section_raw").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("thread_inspector_tab_raw").performScrollTo()
            rule.onNodeWithTag("thread_inspector_section_raw").assertExists()
            capture("$name-raw-landscape")
            var readsBeforeRetry = 0
            scenario.onActivity { readsBeforeRetry = it.fixture.transport.calls.count { call -> call.fn == "orchestration.getTurnItem" } }
            rule.onNodeWithTag("thread_inspector_retry").performScrollTo()
            rule.onNodeWithTag("thread_inspector_retry").performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("thread_inspector_retry") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("thread_inspector_section_raw").assertExists()
            rule.onNodeWithTag("thread_inspector_close").performScrollTo()
            rule.onNodeWithTag("thread_inspector_close").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("thread_inspector").fetchSemanticsNodes().isEmpty() }
            timeline.performScrollToNode(hasTestTag(ThreadScreenTags.message("work/think-reader")))
            rule.onNodeWithTag("thread_inspect_work/think-reader_0").assertIsDisplayed()
            capture("$name-closed-landscape")
            scenario.onActivity { activity ->
                assertEquals(readsBeforeRetry + 1, activity.fixture.transport.calls.count { it.fn == "orchestration.getTurnItem" })
                assertEquals(1, activity.fixture.transport.streamOpens.count { it.threadId == "chat" })
                assertTrue(activity.fixture.transport.calls.none { it.fn == "orchestration.dispatchCommand" })
            }
        }
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("ot3a.capture") != "true") return
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        rule.waitUntil(5_000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName }
        assertEquals("Evidence must not be covered by a system/foreign window", context.packageName,
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(context.getExternalFilesDir(null), "reader-inspector")
        // Opt-in evidence hold: let the device recorder encode this state.
        // This is deliberately not a latency/performance measurement.
        android.os.SystemClock.sleep(800)
        val file = File(output, "$name.png").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
