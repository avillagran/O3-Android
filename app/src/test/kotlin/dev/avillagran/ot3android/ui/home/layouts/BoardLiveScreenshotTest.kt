package dev.avillagran.ot3android.ui.home.layouts

import androidx.compose.ui.test.*
import dev.avillagran.ot3android.core.thread.ThreadConnection
import dev.avillagran.ot3android.core.thread.ThreadMessage
import dev.avillagran.ot3android.core.thread.ThreadView
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.home.toThreadRowModel
import dev.avillagran.ot3android.ui.kit.DesktopThreadRow
import org.junit.Test
import org.robolectric.annotation.Config

class BoardLiveScreenshotTest : ScreenshotTest() {
    private fun board(name: String, live: Boolean) {
        val rows = listOf(fixtureRow("node", "preview"), fixtureRow("node", "working", "working"))
        val columns = taskBoardColumns(rows, SidebarMetadata(), null)
        snapshot(name, beforeCapture = if (live) null else ({
            if (composeRule.onAllNodesWithTag("board_live_preview").fetchSemanticsNodes().isEmpty())
                composeRule.onNodeWithTag("board_live_expand_node/preview").performClick()
        })) {
            TaskBoard(columns, emptySet(), {}, liveMode = live, liveContent = { row, _, expanded ->
                val view = ThreadView(row.threadId, loaded = true, connectionState = ThreadConnection.Live, messages = listOf(
                    ThreadMessage("question", "user", "Revisa las pruebas del tablero.", 1, false, "1"),
                    ThreadMessage("answer", "assistant", "Las pruebas pasaron. Estoy comprobando que la conversación se actualice en vivo.", 2, true, "2")))
                BoardLivePreviewContent(view, expanded, {}, {})
            }) { row -> DesktopThreadRow(row.toThreadRowModel(), {}) }
        }
    }
    @Test fun liveBoard() = board("live", true)
    @Test fun expandedCard() = board("expanded", false)
    @Test @Config(qualifiers = "w320dp-h900dp-port-xhdpi") fun narrowLiveBoard() = board("live-320", true)
}
