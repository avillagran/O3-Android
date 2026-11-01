package dev.avillagran.ot3android.ui.home.layouts

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import androidx.compose.ui.graphics.Color
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.kit.*
import org.junit.Test
import org.robolectric.annotation.Config

class ParentRowScreenshotTest : ScreenshotTest() {
    private fun render(name: String, scale: Float = 1f, sober: Boolean = false) = snapshot(name, beforeCapture = {
        val heights = (0..6).map { composeRule.onNodeWithTag("parity-$it").fetchSemanticsNode().size.height }
        assertEquals("Optional badges, state and child depth must not change row height", 1, heights.distinct().size)
    }) {
        val row = ThreadRowModel(title = "Fix Desktop to Android row parity", projectName = "omarchy-t3-code",
            project = ProjectChipModel("O3", color = Color(0xffed990b)), environmentName = "NODE",
            time = RowTime(TimeKind.AGE, "3m"), status = RowStatus(StatusRole.FAILED, "Failed", "circle-alert"),
            pullRequest = PrBadgeModel(PrState.OPEN, "+24", true), subagents = SubagentsModel(64, 0, false))
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale),
            LocalSidebarRows provides SidebarRowAppearance(enabled = true, sober = sober)) {
            Column {
                listOf(row, row.copy(subagents = null, pullRequest = null),
                    row.copy(subagents = null, pinned = true),
                    row.copy(subagents = SubagentsModel(64, 2, true), status = RowStatus(StatusRole.WORKING, "Working", "loader-circle")),
                    row.copy(subagents = null, status = RowStatus(StatusRole.WAITING, "Waiting", "clock")),
                    row.copy(subagents = null, receding = true, status = RowStatus(StatusRole.DONE, "Done", "check")),
                    row.copy(title = "Child thread", subagents = null, depth = 1, subagent = true)
                ).forEachIndexed { i, model ->
                    DesktopThreadRow(model, {}, Modifier.testTag("parity-$i"), onSubagentsClick = {}, onPullRequestClick = {})
                }
            }
        }
    }

    @Test fun parent() = render("parent")
    @Test @Config(qualifiers = "w320dp-h640dp-port-xhdpi") fun narrow() = render("parent-320")
    @Test @Config(qualifiers = "w320dp-h900dp-port-xhdpi") fun narrowFont160Sober() = render("all-320-160-sober", 1.6f, true)
    @Test @Config(qualifiers = "w320dp-h900dp-port-xhdpi") fun narrowFont160Vivid() = render("all-320-160-vivid", 1.6f, false)
}
