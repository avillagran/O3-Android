package dev.avillagran.ot3android.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.Ot3Radii
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/**
 * ot3's project chip (`ot3:ui/qml/T3ProjectIcon.qml:9-19`): 16 dp, radius 4, the slot colour
 * at [ProjectChipModel.fill] behind the monogram, bold at half the chip height in the slot
 * colour; an emoji draws at 0.8 of the height on no fill. The label scales with the chip, not
 * the font scale, so it always fits. Decorative: the row names the project.
 */
@Composable
fun ProjectChip(model: ProjectChipModel, modifier: Modifier = Modifier, size: Dp = Ot3Sizes.projectChip) {
    val emoji = model.kind == ProjectChipKind.EMOJI
    val fontSize = with(LocalDensity.current) { (size * if (emoji) EMOJI_SCALE else MONOGRAM_SCALE).toSp() }
    Box(
        modifier
            .size(size)
            .background(model.color.copy(alpha = model.fill), RoundedCornerShape(Ot3Radii.projectChip)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            model.label,
            color = model.color,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontSize = fontSize,
                lineHeight = fontSize,
                fontWeight = if (emoji) FontWeight.Normal else FontWeight.Bold,
            ),
        )
    }
}

/**
 * ot3's PR badge (`ot3:ui/qml/T3PullRequestBadge.qml:20-31`): a 12 dp state icon and the
 * caption number in the state colour, 2 dp apart, 16 dp high. With [onClick] it is a button
 * whose touch area Compose widens to 48 dp; its name is ot3's "Pull requests", the number
 * and the state.
 */
@Composable
fun PrBadge(model: PrBadgeModel, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val tone = Ot3Theme.colors.prColor(model.state)
    val state = when (model.state) {
        PrState.OPEN -> stringResource(R.string.ot3_pr_open)
        PrState.MERGED -> stringResource(R.string.ot3_pr_merged)
        PrState.CLOSED -> stringResource(R.string.ot3_pr_closed)
        PrState.DRAFT -> stringResource(R.string.ot3_kit_draft)
        PrState.UNKNOWN -> null
    }
    val name = listOfNotNull(stringResource(R.string.ot3_tab_pull_requests) + " " + model.text, state).joinToString(", ")
    Row(
        modifier
            .height(Ot3Sizes.inlineIcon)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .clearAndSetSemantics { contentDescription = name },
        horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ot3Icon(model.iconName(), null, tint = tone, size = 12.dp)
        Text(model.text, color = tone, style = Ot3Theme.typography.material.bodySmall, maxLines = 1)
    }
}

/**
 * ot3's bar badges (`ot3:ui/qml/T3StatusBadges.qml:8-26`): working (blue) over done (green),
 * as wide as the larger count, and waiting (yellow) beside them when there is any. Pills are
 * 12 dp high, radius 3, 16 / 21 / 27 dp wide; digits 9 sp bold in `background`, "99+" past 99.
 */
@Composable
fun CountPills(model: CountPillsModel, modifier: Modifier = Modifier) {
    val colors = Ot3Theme.colors
    val description = listOf(
        stringResource(R.string.ot3_status_working) to model.working,
        stringResource(R.string.ot3_status_done) to model.done,
        stringResource(R.string.ot3_status_waiting) to model.waiting,
    ).joinToString(", ") { (label, count) -> "$label $count" }
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val width = CountPillFormat.width(maxOf(model.working, model.done))
        Column(verticalArrangement = Arrangement.spacedBy(Ot3Spacing.xs)) {
            CountPill(model.working, colors.working, width)
            CountPill(model.done, colors.done, width)
        }
        if (model.waiting > 0) CountPill(model.waiting, colors.waiting, CountPillFormat.width(model.waiting))
    }
}

@Composable
private fun CountPill(count: Int, color: Color, width: Dp) {
    // Digits keep their size at any font scale, like the pill around them.
    val style = Ot3Theme.typography.countPill
    val fontSize = with(LocalDensity.current) { 9.dp.toSp() }
    Box(
        Modifier
            .width(width)
            .height(Ot3Sizes.countPillHeight)
            .background(color, RoundedCornerShape(Ot3Radii.countPill)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            CountPillFormat.text(count),
            color = Ot3Theme.colors.background,
            style = style.copy(fontSize = fontSize, lineHeight = fontSize),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * A row's 16 dp status icon (UI_SPEC 3.7) in the role colour. `circle-dashed` turns at
 * 112.5°/s while motion allows; a new icon pops in over 460 ms with OutBack 2.4
 * (`ot3:ui/qml/T3ThreadRow.qml:271-286`).
 */
@Composable
fun StatusIcon(status: RowStatus, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val colors = Ot3Theme.colors
    val motion = Ot3Theme.motion
    val spins = StatusIcons.spins(status.iconName)
    val clock by rememberKitClock(spins)
    val pop = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(status.iconName) }
    LaunchedEffect(status.iconName) {
        if (shown != status.iconName) {
            shown = status.iconName
            if (motion.animates) {
                pop.snapTo(POP_FROM)
                pop.animateTo(1f, motion.statusPop())
            }
        }
    }
    Ot3Icon(
        status.iconName,
        contentDescription,
        modifier
            .scale(pop.value)
            .alpha(((pop.value - POP_FROM) / (1f - POP_FROM)).coerceIn(0f, 1f))
            .rotate(if (spins) (clock * STATUS_SPIN_DEGREES_PER_SECOND) % 360f else 0f),
        tint = colors.statusColor(status.role),
        size = Ot3Sizes.inlineIcon,
    )
}

/**
 * ot3's 6 dp health dot (`ot3:ui/qml/T3Panel.qml:1159`): accent while live, `failed` when
 * stale; named Connected / Offline for accessibility.
 */
@Composable
fun ConnectionDot(live: Boolean, modifier: Modifier = Modifier) {
    val colors = Ot3Theme.colors
    val name = stringResource(if (live) R.string.ot3_env_connected else R.string.ot3_env_offline)
    Box(
        modifier
            .size(CONNECTION_DOT)
            .background(if (live) colors.accent else colors.failed, CircleShape)
            .semantics { contentDescription = name },
    )
}

private const val MONOGRAM_SCALE = 0.5f
private const val EMOJI_SCALE = 0.8f
private const val POP_FROM = 0.3f
private val CONNECTION_DOT = 6.dp
