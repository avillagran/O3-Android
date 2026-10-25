package dev.avillagran.ot3android.ui.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.avillagran.ot3android.ui.theme.Ot3Radii
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/**
 * ot3's tooltip (`ot3:ui/style/OmarchyT3/ToolTip.qml:31-49`) on a long press of [content]:
 * plain wrapped bodySmall text on the popup background with a 1 dp `cardBorder` hairline.
 * Omarchy's `Color.tooltip` is not in the theme JSON, so the tooltip takes ot3's popup look
 * (docs/DECISIONS.md, 2026-10-25).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Ot3Tooltip(
    text: String,
    modifier: Modifier = Modifier,
    initiallyVisible: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = Ot3Theme.colors
    val shape = RoundedCornerShape(Ot3Radii.control)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above, Ot3Spacing.sm),
        tooltip = {
            PlainTooltip(
                modifier = Modifier.border(Ot3Sizes.border, colors.cardBorder, shape),
                shape = shape,
                containerColor = colors.background,
                contentColor = colors.foreground,
            ) {
                Text(text, style = Ot3Theme.typography.material.labelMedium)
            }
        },
        state = rememberTooltipState(initialIsVisible = initiallyVisible, isPersistent = initiallyVisible),
        modifier = modifier,
        content = content,
    )
}
