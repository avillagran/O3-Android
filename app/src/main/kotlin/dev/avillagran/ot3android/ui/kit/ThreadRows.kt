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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.Ot3Radii
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/** One Desktop-style two-line row for every layout and thread state.
 * The title and project lines reserve space even without badges. Font scaling grows
 * both lines together, so optional actions never change the height of adjacent rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DesktopThreadRow(
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
    val appearance = LocalSidebarRows.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val titleHeight = maxOf(Ot3Sizes.touchTarget, with(density) { (type.bodyMedium.fontSize * 1.5f).toDp() })
    val metadataHeight = maxOf(24.dp, with(density) { (type.bodySmall.fontSize * 1.5f).toDp() } + 4.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(when {
        pressed -> colors.rowPressed
        selected -> colors.rowSelected
        row.draft -> colors.rowDraft
        else -> Color.Transparent
    }, Ot3Theme.motion.colorChange(), label = "rowFill")
    val shape = RoundedCornerShape(Ot3Radii.row)
    val depth = row.depth.coerceIn(0, MAX_DEPTH)
    val projectColor = if (appearance.projectColors) row.project?.color ?: colors.dim else colors.dim
    val hostColor = appearance.host.color.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.let { Color(it.toColorInt()) }
    val name = rowAccessibilityName(row)
    Box(modifier.fillMaxWidth().height(titleHeight + metadataHeight + 12.dp)
        .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
            onLongClick = onLongClick, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = name }) {
        repeat(depth) { level ->
            Box(Modifier.offset(x = INDENT * level + GUIDE_OFFSET).width(1.dp).fillMaxHeight()
                .background(colors.foreground.copy(alpha = GUIDE_ALPHA)))
        }
        Box(Modifier.padding(start = INDENT * depth, top = 2.dp, bottom = 2.dp).fillMaxWidth().fillMaxHeight()
            .background(fill, shape)
            .then(if (selected || focused) Modifier.border(1.dp, if (focused) colors.accent else colors.foreground.copy(alpha = .22f), shape) else Modifier)) {
            if (!appearance.sober && appearance.projectColors && !row.subagent) {
                Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(projectColor.copy(alpha = if (row.receding) .065f else .13f), projectColor.copy(alpha = .02f), Color.Transparent)), shape))
            }
            if (!appearance.sober) ThreadAura(row, appearance.shelved, Modifier.matchParentSize())
            if (selected || !appearance.sober) Box(Modifier.width(3.dp).fillMaxHeight().padding(vertical = 8.dp)
                .background(if (selected) colors.accent else hostColor ?: projectColor, RoundedCornerShape(2.dp)))
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row(Modifier.height(titleHeight), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (row.draft) Ot3Icon("square-pen", stringResource(R.string.ot3_kit_draft), tint = colors.dim, size = 12.dp)
                    Text(row.title, Modifier.weight(1f).sidebarMarquee(selected),
                        color = if (row.receding) colors.dim else colors.foreground,
                        style = type.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    row.pullRequest?.let { PrBadge(it, onClick = onPullRequestClick) }
                    if (row.pinned) Box(Modifier.width(if (onUnpin != null) Ot3Sizes.touchTarget else 16.dp).height(titleHeight)
                        .then(if (onUnpin != null) Modifier.clickable(role = Role.Button, onClick = onUnpin) else Modifier),
                        contentAlignment = Alignment.Center) {
                        Ot3Icon("pin", stringResource(R.string.ot3_row_unpin), tint = colors.dim, size = 12.dp)
                    }
                    row.subagents?.let { SubagentsToggle(it, onSubagentsClick) }
                    row.time?.label?.let { Text(it, color = if (row.time.kind == TimeKind.COUNTDOWN) colors.working else colors.dim,
                        style = type.bodySmall.copy(fontFamily = if (appearance.sober) androidx.compose.ui.text.font.FontFamily.Monospace else null), maxLines = 1) }
                }
                Row(Modifier.height(metadataHeight), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).background(row.status?.let { colors.statusColor(it.role) } ?: colors.dim, CircleShape))
                    if (row.subagent) Ot3Icon("bot", stringResource(R.string.ot3_kit_subagent), tint = colors.dim, size = 12.dp)
                    Text(row.projectName, Modifier.weight(1f)
                        .then(if (onProjectLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onProjectLongClick) else Modifier),
                        color = colors.dim, style = type.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    row.environmentName?.let { Text(it, Modifier.widthIn(max = 100.dp)
                        .border(1.dp, colors.dim.copy(alpha = .2f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                        color = if (!appearance.sober) hostColor ?: colors.dim else colors.dim,
                        style = type.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(4.dp)
    val expansion = stringResource(if (model.expanded) R.string.about_expanded else R.string.about_collapsed)
    Box(
        Modifier.heightIn(min = Ot3Sizes.touchTarget).widthIn(min = Ot3Sizes.touchTarget)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null,
                role = Role.Button, onClickLabel = name, onClick = onClick) else Modifier)
            .semantics { contentDescription = name; stateDescription = expansion },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = CAPTION_LINE)
                .background(colors.foreground.copy(alpha = if (pressed) .08f else SUBAGENTS_FILL), shape)
                .then(if (focused) Modifier.border(1.dp, colors.accent, shape) else Modifier)
                .padding(horizontal = Ot3Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ot3Icon("bot", null, tint = if (model.working > 0) colors.working else colors.dim, size = 12.dp)
            Text(
                if (model.working > 0) "${model.working}/${model.count}" else model.count.toString(),
                style = Ot3Theme.typography.material.bodySmall.copy(fontFamily = if (LocalSidebarRows.current.sober) androidx.compose.ui.text.font.FontFamily.Monospace else null),
                color = colors.dim,
            )
            val mirror = !model.expanded && LocalLayoutDirection.current == LayoutDirection.Rtl
            Ot3Icon(
                if (model.expanded) "chevron-down" else "chevron-right", null,
                Modifier.scale(scaleX = if (mirror) -1f else 1f, scaleY = 1f),
                tint = colors.dim, size = 12.dp,
            )
        }
    }
}

private val CAPTION_LINE = 20.dp
private val INDENT = 16.dp
private val GUIDE_OFFSET = 15.dp
private const val MAX_DEPTH = 4
private const val GUIDE_ALPHA = 0.14f
private const val SUBAGENTS_FILL = 0.04f


/** Selection/focus replaces hover on touch; reduced motion always leaves an ellipsis. */
@Composable
private fun Modifier.sidebarMarquee(selected: Boolean): Modifier =
    if (LocalSidebarRows.current.marquee && Ot3Theme.motion.animates && selected)
        basicMarquee(iterations = Int.MAX_VALUE, animationMode = MarqueeAnimationMode.Immediately,
            initialDelayMillis = 700, repeatDelayMillis = 1100)
    else this
