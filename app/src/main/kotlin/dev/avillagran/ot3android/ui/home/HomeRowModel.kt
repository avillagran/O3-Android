package dev.avillagran.ot3android.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.home.HomeRow
import dev.avillagran.ot3android.core.home.RowStyle
import dev.avillagran.ot3android.ui.kit.PrBadgeModel
import dev.avillagran.ot3android.ui.kit.PrState
import dev.avillagran.ot3android.ui.kit.ProjectChipModel
import dev.avillagran.ot3android.ui.kit.RowStatus
import dev.avillagran.ot3android.ui.kit.RowTime
import dev.avillagran.ot3android.ui.kit.ThreadRowModel
import dev.avillagran.ot3android.ui.kit.TimeKind
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/**
 * A Home row as the kit draws it: the `sidebar.view` fields one to one (UI_SPEC rule 6). The
 * lib's English label and time are translated here; the chip takes the theme's palette slot
 * when the view had no resolved colour.
 */
@Composable
fun HomeRow.toThreadRowModel(): ThreadRowModel {
    val palette = Ot3Theme.colors.projectPalette
    return ThreadRowModel(
        title = title ?: stringResource(R.string.ot3_row_untitled),
        projectName = projectTitle.orEmpty(),
        project = identity?.let {
            ProjectChipModel.fromLib(
                label = it.monogram,
                kind = it.kind,
                color = it.color,
                colorSlot = it.colorSlot ?: 0,
                fill = it.fill ?: ProjectChipModel.MONOGRAM_FILL.toDouble(),
                palette = palette,
            )
        },
        status = statusLabel.takeIf { it.isNotEmpty() }?.let { RowStatus.fromLib(detail, statusText(detail, it), statusRole) },
        time = RowTime(TimeKind.fromLib(time.kind), timeText(time.label)),
        branch = branch,
        pullRequest = pullRequestBadge?.let {
            PrBadgeModel(PrState.fromLib(it.optString("state")), it.optString("text"), it.optString("kind") == "stack")
        },
        workspaceIcon = if (worktree) WORKTREE_ICON else null,
        environmentName = environmentLabel,
        pinned = pinned,
        subagent = subagent,
        depth = depth,
        // ot3's receding rows: work that needs nothing from the user, and settled threads.
        receding = settled || detail == "working",
    )
}

/** Whether the kit draws [row] as a card or a slim row. */
val HomeRow.slim: Boolean get() = style == RowStyle.Slim

private const val WORKTREE_ICON = "folder-git-2"
