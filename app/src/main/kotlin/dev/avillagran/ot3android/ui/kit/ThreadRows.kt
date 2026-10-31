package dev.avillagran.ot3android.ui.kit

import androidx.core.graphics.toColorInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.MarqueeAnimationMode
import dev.avillagran.ot3android.ui.home.layouts.LocalSidebarRows
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.Ot3Radii
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/**
 * ot3's card row (`ot3:ui/qml/T3ThreadRow.qml:176-324`, UI_SPEC 4.1.3) for active and pinned
 * threads: 82 dp minimum including 2 dp above and below the card, so stacked cards sit 4 dp
 * apart; radius 6, padding 10 × 8. Three lines: draft mark, project chip and name,
 * environment, pin, subagent toggle and the status (or the time); the title; branch, PR badge
 * and machine. The card fills `rowPressed` while pressed, `rowDraft` with a draft and
 * `rowSelected` when [selected] (tablets). Tap opens, long press opens the actions sheet;
 * a long press on the project chip runs [onProjectLongClick] (the project's actions).
 */
@Composable
fun ThreadCardRow(
    row: ThreadRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    onPullRequestClick: (() -> Unit)? = null,
    onSubagentsClick: (() -> Unit)? = null,
    onUnpin: (() -> Unit)? = null,
    onProjectLongClick: (() -> Unit)? = null,
) {
    val colors = Ot3Theme.colors
    val type = Ot3Theme.typography.material
    RowFrame(
        row = row,
        slim = false,
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.heightIn(min = Ot3Sizes.cardRowMinHeight),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Ot3Sizes.cardRowPaddingHorizontal, vertical = Ot3Sizes.cardRowPaddingVertical)
                .alpha(if (row.receding && row.status?.role == StatusRole.WORKING) RECEDING_WORKING_ALPHA else 1f),
            verticalArrangement = Arrangement.spacedBy(Ot3Spacing.xxs),
        ) {
            CaptionLine(row, slim = false, onSubagentsClick = onSubagentsClick, onUnpin = onUnpin, onChipLongClick = onProjectLongClick?.let { it to onClick }) {
                Text(
                    listOfNotNull(row.projectName.takeIf { it.isNotEmpty() }, row.environmentName).joinToString(" · "),
                    Modifier.weight(1f),
                    style = type.bodySmall,
                    color = colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.padding(top = Ot3Spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.title,
                    Modifier.weight(1f).sidebarMarquee(selected),
                    style = type.bodyMedium.copy(fontWeight = if (row.receding) FontWeight.Normal else FontWeight.Medium),
                    color = if (row.receding) colors.dim else colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                rowDoneAge(row)?.let { Text(it, style = type.bodySmall, color = colors.dim, maxLines = 1) }
            }
            Row(
                Modifier.height(Ot3Sizes.inlineIcon),
                horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val faint = colors.foreground.copy(alpha = WORKSPACE_ALPHA)
                row.workspaceIcon?.let { Ot3Icon(it, null, tint = faint, size = 12.dp) }
                Text(
                    row.branch.orEmpty(),
                    Modifier.weight(1f),
                    style = type.bodySmall,
                    color = faint,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                row.pullRequest?.let { PrBadge(it, onClick = onPullRequestClick) }
                row.machineIcon?.let { Ot3Icon(it, null, tint = colors.dim, size = 12.dp) }
            }
        }
    }
}

/**
 * ot3's slim row for settled, snoozed and subagent threads: 48 dp (ot3's 36 grown to the
 * touch minimum), the project chip at 0.4 opacity (or the subagent icon), the title in body
 * text, the PR badge and the time; a snooze countdown is in `working`, a subagent shows its
 * status label. Subagents indent 16 dp per [ThreadRowModel.depth] with ot3's tree guides.
 */
@Composable
fun ThreadSlimRow(
    row: ThreadRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    onPullRequestClick: (() -> Unit)? = null,
    onSubagentsClick: (() -> Unit)? = null,
    onUnpin: (() -> Unit)? = null,
) {
    RowFrame(
        row = row,
        slim = true,
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.heightIn(min = Ot3Sizes.slimRowHeight),
    ) {
        Column(Modifier.padding(horizontal = Ot3Sizes.cardRowPaddingHorizontal, vertical = 4.dp)) {
            CaptionLine(row, slim = true, onSubagentsClick = onSubagentsClick, onUnpin = onUnpin) {
                Text(
                    row.title,
                    Modifier.weight(1f).sidebarMarquee(selected),
                    style = Ot3Theme.typography.material.bodyMedium,
                    color = if (row.receding) Ot3Theme.colors.dim else Ot3Theme.colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.pullRequest?.let { PrBadge(it, onClick = onPullRequestClick) }
            }
            val metadata = listOfNotNull(row.projectName.takeIf { LocalSidebarRows.current.enabled && it.isNotEmpty() }, row.environmentName, row.branch).joinToString(" · ")
            if (metadata.isNotEmpty()) Text(
                metadata,
                style = Ot3Theme.typography.material.bodySmall,
                color = Ot3Theme.colors.dim,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

/** The pressable card behind both rows, with the subagent indent and tree guides. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowFrame(
    row: ThreadRowModel,
    slim: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val colors = Ot3Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(
        when {
            pressed -> colors.rowPressed
            selected -> colors.rowSelected
            row.draft -> colors.rowDraft
            LocalSidebarRows.current.enabled && !LocalSidebarRows.current.sober && LocalSidebarRows.current.projectColors -> row.project?.color?.copy(alpha = .08f) ?: Color.Transparent
            else -> Color.Transparent
        },
        Ot3Theme.motion.colorChange(),
        label = "rowFill",
    )
    val shape = RoundedCornerShape(Ot3Radii.row)
    val depth = row.depth.coerceIn(0, MAX_DEPTH)
    val name = rowAccessibilityName(row)
    Box(
        modifier
            .then(if (slim) Modifier.height(IntrinsicSize.Min) else Modifier)
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = name },
    ) {
        repeat(depth) { level ->
            Box(
                Modifier
                    .offset(x = INDENT * level + GUIDE_OFFSET)
                    .width(Ot3Sizes.border)
                    .fillMaxHeight()
                    .background(colors.foreground.copy(alpha = GUIDE_ALPHA)),
            )
        }
        Box(
            Modifier
                .padding(start = INDENT * depth)
                .padding(vertical = if (slim) 0.dp else Ot3Spacing.xxs)
                .fillMaxWidth()
                .then(if (slim) Modifier.fillMaxHeight() else Modifier.heightIn(min = Ot3Sizes.cardRowMinHeight - Ot3Spacing.sm))
                .background(fill, shape)
                .then(if (focused) Modifier.border(Ot3Sizes.border, colors.accent, shape) else Modifier),
        ) {
            if (!LocalSidebarRows.current.sober) ThreadAura(row, slim, Modifier.matchParentSize())
            val hostColor = LocalSidebarRows.current.host.color.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
                ?.let { Color(it.toColorInt()) }
            if (hostColor != null && !LocalSidebarRows.current.sober) Box(Modifier.width(if (LocalSidebarRows.current.sober) 2.dp else 4.dp).fillMaxHeight().background(hostColor))
            content()
        }
    }
}

/**
 * The first line of both rows (`ot3:ui/qml/T3ThreadRow.qml:220-288`): draft mark, subagent
 * icon or project chip, [middle] (project name on cards, title and PR on slim rows), pin,
 * subagent toggle, then the status or the time.
 */
@Composable
private fun CaptionLine(
    row: ThreadRowModel,
    slim: Boolean,
    onSubagentsClick: (() -> Unit)?,
    onUnpin: (() -> Unit)?,
    /** Long press on the chip, and the row's tap so a tap on the chip still opens the row. */
    onChipLongClick: Pair<() -> Unit, () -> Unit>? = null,
    middle: @Composable RowScope.() -> Unit,
) {
    val colors = Ot3Theme.colors
    val captionSlim = slim && (!LocalSidebarRows.current.enabled || LocalSidebarRows.current.shelved)
    val trailing = rowTrailing(row, captionSlim)
    Row(
        Modifier.heightIn(min = CAPTION_LINE),
        horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.draft) Ot3Icon("square-pen", stringResource(R.string.ot3_kit_draft), tint = colors.dim, size = 13.dp)
        if (row.subagent) {
            val working = row.status?.role == StatusRole.WORKING
            Ot3Icon(
                if (working) "loader-circle" else "bot",
                stringResource(R.string.ot3_kit_subagent),
                tint = row.status?.let { colors.statusColor(it.role) } ?: colors.dim,
                size = 14.dp,
            )
        } else {
            row.project?.let { chip ->
                val gesture = onChipLongClick?.let { (longClick, click) ->
                    Modifier.combinedClickable(onClick = click, onLongClick = longClick)
                } ?: Modifier
                if (LocalSidebarRows.current.enabled && LocalSidebarRows.current.sober) {
                    Box(gesture.size(16.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(6.dp).background(if (LocalSidebarRows.current.projectColors) chip.color else colors.dim, CircleShape))
                    }
                } else ProjectChip(if (LocalSidebarRows.current.projectColors) chip else chip.copy(color = colors.dim), gesture.alpha(if (slim) SLIM_CHIP_ALPHA else 1f))
            }
        }
        if (slim && LocalSidebarRows.current.enabled) row.machineIcon?.let {
            val color = LocalSidebarRows.current.host.color.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
                ?.let { value -> Color(value.toColorInt()) } ?: colors.dim
            Ot3Icon(it, null, tint = color, size = 12.dp)
        }
        middle()
        if (row.pinned) {
            if (onUnpin != null) {
                Box(Modifier.clickable(role = Role.Button, onClick = onUnpin)) {
                    Ot3Icon("pin", stringResource(R.string.ot3_row_unpin), tint = colors.dim)
                }
            } else {
                Ot3Icon("pin", null, tint = colors.dim)
            }
        }
        row.subagents?.let { SubagentsToggle(it, onSubagentsClick) }
        if (row.status != null && !captionSlim && trailing?.role == TrailingRole.STATUS) {
            StatusIcon(row.status)
        }
        if (trailing != null) {
            Text(
                trailing.text,
                style = Ot3Theme.typography.material.bodySmall.copy(
                    fontWeight = if (trailing.medium) FontWeight.Medium else FontWeight.Normal,
                ),
                color = when (trailing.role) {
                    TrailingRole.STATUS -> colors.statusColor(row.status!!.role)
                    TrailingRole.WORKING -> colors.working
                    TrailingRole.DIM -> colors.dim
                },
                maxLines = 1,
            )
        }
    }
}

/** "Show {count} subagents": bot, "working/count" or "count", chevron (`ot3:ui/qml/T3ThreadRow.qml:244-256`). */
@Composable
private fun SubagentsToggle(model: SubagentsModel, onClick: (() -> Unit)?) {
    val colors = Ot3Theme.colors
    val name = stringResource(
        if (model.expanded) R.string.ot3_row_hide_subagents else R.string.ot3_row_show_subagents,
        model.count.toString(),
    )
    Row(
        Modifier
            .height(CAPTION_LINE)
            .background(colors.foreground.copy(alpha = SUBAGENTS_FILL), RoundedCornerShape(Ot3Radii.projectChip))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = name, onClick = onClick) else Modifier)
            .semantics { contentDescription = name }
            .padding(horizontal = Ot3Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ot3Icon("bot", null, tint = if (model.working > 0) colors.working else colors.dim, size = 12.dp)
        Text(
            if (model.working > 0) "${model.working}/${model.count}" else model.count.toString(),
            style = Ot3Theme.typography.material.bodySmall,
            color = colors.dim,
        )
        // chevron-right points to the end of the line in either direction.
        val mirror = !model.expanded && LocalLayoutDirection.current == LayoutDirection.Rtl
        Ot3Icon(
            if (model.expanded) "chevron-down" else "chevron-right",
            null,
            Modifier.scale(scaleX = if (mirror) -1f else 1f, scaleY = 1f),
            tint = colors.dim,
            size = 12.dp,
        )
    }
}

private val CAPTION_LINE = 20.dp
private val INDENT = 16.dp
private val GUIDE_OFFSET = 15.dp
private const val MAX_DEPTH = 4
private const val GUIDE_ALPHA = 0.14f
private const val WORKSPACE_ALPHA = 0.4f
private const val SLIM_CHIP_ALPHA = 0.4f
private const val SUBAGENTS_FILL = 0.04f
private const val RECEDING_WORKING_ALPHA = 0.7f


/** Selection/focus replaces hover on touch; reduced motion always leaves an ellipsis. */
@Composable
private fun Modifier.sidebarMarquee(selected: Boolean): Modifier =
    if (LocalSidebarRows.current.marquee && Ot3Theme.motion.animates && selected)
        basicMarquee(iterations = Int.MAX_VALUE, animationMode = MarqueeAnimationMode.Immediately,
            initialDelayMillis = 700, repeatDelayMillis = 1100)
    else this
