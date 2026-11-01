package dev.avillagran.ot3android.ui.home.layouts

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.kit.*
import org.junit.Test
import org.robolectric.annotation.Config

class ParentRowScreenshotTest : ScreenshotTest() {
    private fun render(name: String) = snapshot(name) {
        val row = ThreadRowModel(title = "Fix Desktop to Android row parity", projectName = "omarchy-t3-code",
            project = ProjectChipModel("O3", color = Color(0xffed990b)), environmentName = "NODE",
            time = RowTime(TimeKind.AGE, "3m"), status = RowStatus(StatusRole.FAILED, "Failed", "circle-alert"),
            pullRequest = PrBadgeModel(PrState.OPEN, "+24", true), subagents = SubagentsModel(64, 0, false))
        Column {
            ThreadCardRow(row, {}, onSubagentsClick = {})
            ThreadSlimRow(row.copy(subagents = SubagentsModel(64, 2, true)), {}, onSubagentsClick = {})
            ThreadSlimRow(row.copy(title = "Child thread", subagents = null, depth = 1, subagent = true), {})
        }
    }
    @Test fun parent() = render("parent")
    @Test @Config(qualifiers = "w320dp-h640dp-port-xhdpi") fun narrow() = render("parent-320")
}
