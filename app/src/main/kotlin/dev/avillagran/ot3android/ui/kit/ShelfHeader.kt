package dev.avillagran.ot3android.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/** A shelf label's colour: Working in `working`, Snoozed and Settled in `dim` (UI_SPEC 4.1.4). */
enum class ShelfTone { WORKING, DIM }

/**
 * ot3's shelf header (`ot3:ui/qml/T3Panel.qml:1225-1367`): the caption label ("Working (1)"),
 * an inline 1 dp `divider` rule and a 12 dp chevron, down while expanded and up while
 * collapsed, turning over ot3's shelf move (520 ms InOutCubic). Drawn 40 dp high (radius 5,
 * 5 dp inside padding) in a 48 dp target; Show / Hide is the click label, with the expand
 * and collapse actions for TalkBack.
 */
@Composable
fun ShelfHeader(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ShelfTone = ShelfTone.DIM,
) {
    val colors = Ot3Theme.colors
    val motion = Ot3Theme.motion
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(if (pressed) colors.rowPressed else Color.Transparent, motion.colorChange(), label = "shelfFill")
    val turn by animateFloatAsState(if (expanded) 0f else 180f, motion.shelfMove(), label = "shelfChevron")
    val show = stringResource(R.string.ot3_kit_show)
    val hide = stringResource(R.string.ot3_kit_hide)
    val shape = RoundedCornerShape(SHELF_RADIUS)
    Box(
        modifier
            .fillMaxWidth()
            .height(Ot3Sizes.touchTarget)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = if (expanded) hide else show,
                onClick = onToggle,
            )
            .semantics {
                if (expanded) {
                    collapse(hide) { onToggle(); true }
                } else {
                    expand(show) { onToggle(); true }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Ot3Sizes.shelfHeaderHeight)
                .background(fill, shape)
                .then(if (focused) Modifier.border(Ot3Sizes.border, colors.accent, shape) else Modifier)
                .padding(horizontal = SHELF_PADDING),
            horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = Ot3Theme.typography.material.bodySmall,
                color = if (tone == ShelfTone.WORKING) colors.working else colors.dim,
                maxLines = 1,
            )
            Box(
                Modifier
                    .weight(1f)
                    .widthIn(min = Ot3Spacing.lg)
                    .height(Ot3Sizes.border)
                    .background(colors.divider),
            )
            Ot3Icon("chevron-down", null, Modifier.rotate(turn), tint = colors.dim, size = 12.dp)
        }
    }
}

/**
 * A shelf's rows outside a lazy list, revealed and hidden with ot3's shelf move; inside a
 * `LazyColumn` the rows come and go with `animateItem` instead.
 */
@Composable
fun ShelfContent(expanded: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val motion = Ot3Theme.motion
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = expandVertically(motion.shelfMove()) + fadeIn(motion.shelfMove()),
        exit = shrinkVertically(motion.shelfMove()) + fadeOut(motion.shelfMove()),
    ) {
        Column(content = content)
    }
}

private val SHELF_RADIUS = 5.dp
private val SHELF_PADDING = 5.dp
