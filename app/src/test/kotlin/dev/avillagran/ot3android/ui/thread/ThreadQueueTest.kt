package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ThreadQueueTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun queue() = JSONObject("""{"held":true,"canSteer":true,"canReorder":true,"activeRunId":"active","items":[{"runId":"first","text":"First instruction","available":true,"attachments":[]},{"runId":"second","text":"Second instruction","available":true,"attachments":[]}]}""")

    @Test fun pausedQueueActionsUseTheVisibleRunAndRustCapabilities() {
        val actions = mutableListOf<JSONObject>()
        var edited: JSONObject? = null
        rule.setContent { Ot3Theme { ThreadQueue(queue(), true, { actions += it }, { edited = it }) } }
        rule.onNodeWithText("Resume").performClick()
        rule.runOnIdle { assertEquals("resume", actions.single().getString("action")) }
        rule.onNodeWithTag("thread_queue").performClick()
        rule.onNodeWithText("Move up").performClick()
        rule.runOnIdle {
            assertEquals("second", actions.last().getString("runId"))
            assertEquals("first", actions.last().getString("beforeRunId"))
        }
        rule.onAllNodesWithText("Steer the active run")[0].performClick()
        rule.runOnIdle { assertEquals("active", actions.last().getString("targetRunId")) }
        rule.onAllNodesWithText("Edit in composer")[0].performClick()
        rule.runOnIdle { assertEquals("first", edited!!.getString("runId")) }
    }

    @Test fun offlineQueueRemainsVisibleButCannotMutateOrOfferUnsupportedSteering() {
        val data = queue().put("canSteer", false).put("canReorder", false)
        rule.setContent { Ot3Theme { ThreadQueue(data, false, { error("offline mutation") }, { error("offline edit") }) } }
        rule.onNodeWithText("Resume").assertIsNotEnabled()
        rule.onNodeWithTag("thread_queue").performClick()
        rule.onAllNodesWithText("Remove from queue")[0].assertIsNotEnabled()
        rule.onAllNodesWithText("Edit in composer")[0].assertIsNotEnabled()
        rule.onNodeWithText("Steer the active run").assertDoesNotExist()
        rule.onNodeWithText("Move up").assertDoesNotExist()
    }
}
