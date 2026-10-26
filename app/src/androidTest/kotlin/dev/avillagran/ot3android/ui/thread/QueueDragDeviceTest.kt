package dev.avillagran.ot3android.ui.thread

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real graph, native projection/builder and durable outbox; synthetic server, never production. */
class QueueDragDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun tokyoPendingDropWaitsForAuthoritativeStreamOrder() = launch(true) { scenario ->
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performTextReplacement("Keep my unsent draft")
        closeSoftKeyboard()
        openQueue(); capture("tokyo", "queue")
        val hold = CompletableDeferred<Unit>()
        scenario.onActivity { it.fixture.queueHold = hold }
        try {
            beginEndDrag("q1"); capture("tokyo", "drag"); endDrag()
            waitCalls(scenario, 1)
            rule.onNodeWithTag("queue_grip_q1").assertIsNotEnabled()
            assertBefore("q1", "q2") // Neither dragging nor pending dispatch changes display order.
            hold.complete(Unit)
            waitEnabled("q1"); assertBefore("q1", "q2")
            scenario.onActivity { activity -> activity.fixture.viewModelScope.launch { activity.fixture.publishQueueOrder(listOf("q2", "q3", "q4", "q1")) } }
            rule.waitUntil(15_000) { top("q1") > top("q4") }
            scenario.onActivity {
                val command = commands(it).single()
                assertEquals("queued-run.reorder", command.getString("type")); assertEquals("q1", command.getString("runId")); assertTrue(command.isNull("beforeRunId"))
            }
            capture("tokyo", "authoritative")
        } finally { hold.complete(Unit) }
    }
    @Test fun latteUnknownRetryIsByteIdenticalAndQueueEditPreservesDraftAndAttachments() = launch(false) { scenario ->
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performTextReplacement("Keep my unsent draft")
        closeSoftKeyboard()
        scenario.onActivity { it.fixture.queueUnknownFirst = true }
        openQueue(); capture("latte", "queue")
        beginEndDrag("q1"); capture("latte", "drag"); endDrag()
        waitCalls(scenario, 1)
        var first: JSONObject? = null
        scenario.onActivity { first = commands(it).single() }
        val id = first!!.getString("commandId")
        val retry = hasTestTag(ThreadScreenTags.resend(id)) and hasAnyAncestor(hasTestTag("queue_feedback"))
        rule.waitUntil(15_000) { rule.onAllNodes(retry).fetchSemanticsNodes().isNotEmpty() }
        capture("latte", "unknown")
        rule.onNode(retry).performClick(); waitCalls(scenario, 2); waitEnabled("q1")
        scenario.onActivity { assertEquals(first.toString(), commands(it)[1].toString()) }
        assertBefore("q1", "q2")
        rule.onNodeWithTag("queue_edit_q1").performClick()
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains("Queued instruction q1")
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performTextReplacement("Revised queued instruction")
        closeSoftKeyboard()
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(15_000) { ApplicationProvider.getApplicationContext<Context>().resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains("Revised queued instruction")
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitUntil(15_000) { ApplicationProvider.getApplicationContext<Context>().resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        rule.onNodeWithTag(ThreadScreenTags.SEND).assertIsEnabled().performClick()
        waitCalls(scenario, 3)
        rule.waitUntil(15_000) { rule.onNodeWithTag(ThreadScreenTags.COMPOSER).fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "Keep my unsent draft" }
        scenario.onActivity {
            val edit = commands(it)[2]
            assertEquals("queued-run.edit", edit.getString("type"))
            assertEquals("notes.txt", edit.getJSONArray("attachments").getJSONObject(0).getString("name"))
            assertEquals("/fixture/notes.txt", edit.getJSONObject("context").getJSONArray("filePaths").getString(0))
        }
    }
    @Test fun rejectionRemainsVisibleInsideSheetWithoutChangingServerOrder() = launch(true) { scenario ->
        scenario.onActivity { it.fixture.queueRejectNext = true }
        openQueue(); beginEndDrag("q1"); endDrag(); waitCalls(scenario, 1); waitEnabled("q1")
        val feedback = hasText("The server refused it.") and hasAnyAncestor(hasTestTag("queue_feedback"))
        rule.waitUntil(15_000) { rule.onAllNodes(feedback).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(feedback).assertIsDisplayed(); assertBefore("q1", "q2")
        beginEndDrag("q1"); endDrag(); waitCalls(scenario, 2)
        scenario.onActivity {
            val requests = commands(it)
            assertNotEquals(requests[0].getString("commandId"), requests[1].getString("commandId"))
        } // A definite rejection clears the outbox; a fresh explicit gesture has a new ID.
    }
    @Test fun noOpCancelStreamUpdateAndOfflineDuringDragNeverDispatch() = launch(true) { scenario ->
        openQueue()
        val height = rule.onNodeWithTag("queue_row_q1").fetchSemanticsNode().boundsInRoot.height
        rule.onNodeWithTag("queue_grip_q1").performTouchInput { down(center); moveTo(center + Offset(0f, height * .7f), 350); up() }
        beginEndDrag("q1"); rule.onNodeWithTag("queue_list").performTouchInput { cancel() }
        assertCount(scenario, 0)
        beginEndDrag("q1")
        scenario.onActivity { activity -> activity.fixture.viewModelScope.launch { activity.fixture.publishQueueOrder(listOf("q2", "q1", "q3", "q4")) } }
        rule.waitUntil(15_000) { top("q2") < top("q1") }; endDrag(); assertCount(scenario, 0)
        beginEndDrag("q2")
        scenario.onActivity { activity -> activity.fixture.viewModelScope.launch { activity.fixture.connections.forget("node") } }
        rule.waitUntil(15_000) { rule.onNodeWithTag("queue_grip_q2").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) != null }
        endDrag(); assertCount(scenario, 0)
    }
    private fun launch(dark: Boolean, test: (ActivityScenario<WorkspaceFixtureActivity>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java).putExtra("dark", dark).putExtra("request", "queue")).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("thread_queue").fetchSemanticsNodes().isNotEmpty() }
            test(scenario)
        }
    }
    private fun openQueue() { rule.onNodeWithTag("thread_queue").performClick(); waitEnabled("q1") }
    private fun beginEndDrag(run: String) {
        val grip = rule.onNodeWithTag("queue_grip_$run").fetchSemanticsNode().boundsInRoot
        val bottom = rule.onNodeWithTag("queue_row_q4").fetchSemanticsNode().boundsInRoot.bottom
        val distance = bottom - grip.center.y + 12f
        rule.onNodeWithTag("queue_grip_$run").performTouchInput { down(center); moveTo(center + Offset(0f, distance), 500) }
    }
    private fun endDrag() { rule.onNodeWithTag("queue_list").performTouchInput { up() } }
    private fun top(run: String) = rule.onNodeWithTag("queue_row_$run").fetchSemanticsNode().boundsInRoot.top
    private fun assertBefore(a: String, b: String) = assertTrue(top(a) < top(b))
    private fun commands(activity: WorkspaceFixtureActivity) = activity.fixture.transport.calls.filter { it.fn == "orchestration.dispatchCommand" }.map { it.args }
    private fun assertCount(scenario: ActivityScenario<WorkspaceFixtureActivity>, expected: Int) { scenario.onActivity { assertEquals(expected, commands(it).size) } }
    private fun waitCalls(scenario: ActivityScenario<WorkspaceFixtureActivity>, expected: Int) {
        var count = 0
        rule.waitUntil(15_000) { scenario.onActivity { count = commands(it).size }; count == expected }
    }
    private fun waitEnabled(run: String) { rule.waitUntil(15_000) { rule.onNodeWithTag("queue_grip_$run").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null } }
    private fun capture(theme: String, name: String) {
        if (InstrumentationRegistry.getArguments().getString("queueEvidence") != "true") return
        rule.waitForIdle(); Thread.sleep(600) // Opt-in compositor/recording wait, never ordinary CI.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "queue-$theme-$name.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
