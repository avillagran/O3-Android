package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ThreadQueueTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun queue() = JSONObject("""{"held":true,"canSteer":true,"canReorder":true,"activeRunId":"active","items":[{"runId":"first","text":"First instruction","available":true,"attachments":[]},{"runId":"second","text":"Second instruction","available":true,"attachments":[]},{"runId":"third","text":"Third instruction","available":true,"attachments":[]}]}""")
    private fun open() { rule.onNodeWithTag("thread_queue").performClick() }
    @Test fun pausedQueueActionsUseTheVisibleRunAndRustCapabilities() {
        val actions = mutableListOf<JSONObject>()
        var edited: JSONObject? = null
        rule.setContent { Ot3Theme { ThreadQueue(queue(), true, { actions += it }, { edited = it }) } }
        rule.onNodeWithText("Resume").performClick(); assertEquals("resume", actions.single().getString("action"))
        open()
        rule.onNodeWithTag("queue_grip_second").performClick()
        rule.onNodeWithText("Move up").performClick()
        assertEquals("second", actions.last().getString("runId")); assertEquals("first", actions.last().getString("beforeRunId"))
        rule.onNodeWithTag("queue_steer_first").performClick(); assertEquals("active", actions.last().getString("targetRunId"))
        rule.onNodeWithTag("queue_edit_first").performClick(); assertEquals("first", edited!!.getString("runId"))
    }
    @Test fun validDragToEndDispatchesOnceWithoutChangingVisibleOrder() {
        val actions = mutableListOf<JSONObject>()
        rule.setContent { Ot3Theme { ThreadQueue(queue(), true, { actions += it }, {}) } }
        open(); drag("first", 3f)
        assertEquals(1, actions.size); assertEquals("first", actions.single().getString("runId")); assertTrue(actions.single().isNull("beforeRunId"))
        assertTrue(rule.onNodeWithTag("queue_row_first").fetchSemanticsNode().boundsInRoot.top < rule.onNodeWithTag("queue_row_second").fetchSemanticsNode().boundsInRoot.top)
    }
    @Test fun sameAdjacentTapAndCanceledDragsDispatchNothing() {
        val actions = mutableListOf<JSONObject>()
        rule.setContent { Ot3Theme { ThreadQueue(queue(), true, { actions += it }, {}) } }
        open(); drag("first", -.7f); drag("first", .7f)
        val grip = rule.onNodeWithTag("queue_grip_first")
        grip.performTouchInput { down(center); moveTo(center + Offset(0f, 180f), 400); cancel() }
        grip.performClick() // A tap opens step controls, never dispatches a reorder.
        assertTrue(actions.isEmpty())
    }
    @Test fun offlinePendingAndCapabilityChangesCancelAnOngoingGesture() {
        var enabled by mutableStateOf(true)
        var data by mutableStateOf(queue())
        var owner by mutableStateOf(1)
        val actions = mutableListOf<JSONObject>()
        rule.setContent { Ot3Theme { ThreadQueue(data, enabled, { actions += it }, {}, dragOwner = owner) } }
        open()
        fun begin() { rule.onNodeWithTag("queue_grip_first").performTouchInput { down(center); moveTo(center + Offset(0f, 180f), 400) } }
        fun end() { rule.onNodeWithTag("queue_list").performTouchInput { up() } }
        begin(); rule.runOnIdle { enabled = false }; end(); assertTrue(actions.isEmpty())
        rule.runOnIdle { enabled = true }; begin(); rule.runOnIdle { data = JSONObject(data.toString()).put("canReorder", false) }; end(); assertTrue(actions.isEmpty())
        rule.runOnIdle { data = queue() }; begin(); rule.runOnIdle { owner++ }; end(); assertTrue(actions.isEmpty())
        begin(); rule.runOnIdle { data = queue().also { it.getJSONArray("items").getJSONObject(0).put("pending", true) } }; end(); assertTrue(actions.isEmpty())
    }
    @Test fun authoritativeOrderChangeCancelsButHarmlessJsonRecreationDoesNot() {
        var data by mutableStateOf(queue())
        val actions = mutableListOf<JSONObject>()
        rule.setContent { Ot3Theme { ThreadQueue(data, true, { actions += it }, {}) } }
        open()
        val grip = rule.onNodeWithTag("queue_grip_first")
        grip.performTouchInput { down(center); moveTo(center + Offset(0f, 180f), 400) }
        rule.runOnIdle { data = JSONObject(data.toString()) }
        grip.performTouchInput { up() }; assertEquals(1, actions.size)
        grip.performTouchInput { down(center); moveTo(center + Offset(0f, 180f), 400) }
        rule.runOnIdle { val rows = data.getJSONArray("items"); data = queue().put("items", org.json.JSONArray().put(rows[1]).put(rows[0]).put(rows[2])) }
        rule.onNodeWithTag("queue_list").performTouchInput { up() }; assertEquals(1, actions.size)
    }
    @Test fun accessibleStepDownTargetsNextGapAndNullAtEnd() {
        val actions = mutableListOf<JSONObject>()
        rule.setContent { Ot3Theme { ThreadQueue(queue(), true, { actions += it }, {}) } }
        open()
        rule.onNodeWithTag("queue_grip_first").performClick(); rule.onNodeWithText("Move down").performClick()
        assertEquals("third", actions.last().getString("beforeRunId"))
        rule.onNodeWithTag("queue_grip_second").performClick(); rule.onNodeWithText("Move down").performClick()
        assertEquals("second", actions.last().getString("runId")); assertTrue(actions.last().isNull("beforeRunId"))
    }
    @Test fun dismissDuringGestureDoesNotDispatch() {
        val actions = mutableListOf<JSONObject>()
        rule.setContent { Ot3Theme { ThreadQueue(queue(), true, { actions += it }, {}) } }
        open()
        rule.onNodeWithTag("queue_grip_first").performTouchInput { down(center); moveTo(center + Offset(0f, 180f), 400) }
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss)).onFirst()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss) { it() }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("queue_list").assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }
    @Test fun offlineQueueRemainsVisibleButCannotMutateOrOfferUnsupportedSteering() {
        val data = queue().put("canSteer", false).put("canReorder", false)
        rule.setContent { Ot3Theme { ThreadQueue(data, false, { error("offline mutation") }, { error("offline edit") }) } }
        rule.onNodeWithText("Resume").assertIsNotEnabled(); open()
        rule.onNodeWithTag("queue_remove_first").assertIsNotEnabled(); rule.onNodeWithTag("queue_edit_first").assertIsNotEnabled()
        rule.onNodeWithTag("queue_grip_first").assertIsNotEnabled(); rule.onNodeWithTag("queue_steer_first").assertDoesNotExist()
    }
    private fun drag(run: String, rows: Float) {
        val height = rule.onNodeWithTag("queue_row_$run").fetchSemanticsNode().boundsInRoot.height
        rule.onNodeWithTag("queue_grip_$run").performTouchInput { down(center); moveTo(center + Offset(0f, height * rows), 500); up() }
    }
}
