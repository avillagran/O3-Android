package dev.avillagran.ot3android.ui.home.layouts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class TaskBoardLiveUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun globalAndIndividualLiveControlsOnlyActivateViewportCards() {
        val rows = (0..29).map { fixtureRow("node", "$it") } + fixtureRow("node", "done", "done")
        val columns = taskBoardColumns(rows, SidebarMetadata(), null)
        val live = mutableStateOf(false)
        val collapsed = mutableStateOf(emptySet<String>())
        val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        rule.setContent { Ot3Theme {
            TaskBoard(columns, collapsed.value, { key -> collapsed.value = if (key in collapsed.value) collapsed.value - key else collapsed.value + key },
                liveMode = live.value, onLiveMode = { live.value = it }, liveContent = { row, watching, _ ->
                    DisposableEffect(row.key, watching) {
                        if (watching) active.add(row.key)
                        onDispose { active.remove(row.key) }
                    }
                    Text("Preview ${row.threadId}")
                }) { row -> Text(row.threadId) }
        } }
        assertTrue(active.isEmpty())
        rule.onNodeWithTag("board_live_expand_node/0").performClick()
        rule.waitUntil { active == setOf("node/0") }
        rule.onNodeWithText("Preview 0").assertExists()
        rule.onNodeWithTag("task_board_live_mode").performClick()
        rule.waitUntil { active.size > 1 }
        assertTrue(active.size < rows.size)
        rule.onNodeWithTag("task_board_columns").performScrollToNode(hasTestTag("task_board_Done"))
        rule.waitUntil { active == setOf("node/done") }
        rule.onNodeWithTag("task_board_toggle_Done").performClick()
        rule.waitForIdle()
        rule.waitUntil { active.isEmpty() }
        rule.onNodeWithTag("task_board_live_mode").performClick()
        assertFalse(live.value)
    }
}
