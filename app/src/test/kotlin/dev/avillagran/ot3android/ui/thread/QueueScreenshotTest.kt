package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import dev.avillagran.ot3android.testing.ScreenshotTest
import org.json.JSONObject
import org.junit.Test

class QueueScreenshotTest : ScreenshotTest() {
    @Test fun tokyo() = queue("tokyo-night")
    @Test fun latte() = queue("catppuccin-latte")
    private fun queue(theme: String) {
        val data = JSONObject("""{"canReorder":true,"canSteer":true,"activeRunId":"active","items":[{"runId":"q1","text":"Queued instruction q1","available":true,"attachments":[{"name":"notes.txt"}]},{"runId":"q2","text":"Queued instruction q2","available":true},{"runId":"q3","text":"Queued instruction q3","available":true},{"runId":"q4","text":"Queued instruction q4","available":true}]}""")
        snapshot("strip", themes = listOf(theme)) { Box(Modifier.fillMaxSize()) { ThreadQueue(data, true, {}, {}) } }
        composeRule.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("thread_queue"))).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        composeRule.mainClock.advanceTimeBy(1000); composeRule.waitForIdle()
        captureScreenRoboImage("${roborazziSystemPropertyOutputDirectory()}/QueueScreenshotTest/sheet.$theme.png")
        val grip = composeRule.onNodeWithTag("queue_grip_q1").fetchSemanticsNode().boundsInRoot
        val bottom = composeRule.onNodeWithTag("queue_row_q4").fetchSemanticsNode().boundsInRoot.bottom
        composeRule.onNodeWithTag("queue_grip_q1").performTouchInput { down(center); moveTo(center + Offset(0f, bottom - grip.center.y + 12f), 500) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("queue_insert_4").assertExists()
        captureScreenRoboImage("${roborazziSystemPropertyOutputDirectory()}/QueueScreenshotTest/drag.$theme.png")
        composeRule.onNodeWithTag("queue_list").performTouchInput { cancel() }
    }
}
