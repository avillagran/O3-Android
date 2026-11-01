package dev.avillagran.ot3android.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import dev.avillagran.ot3android.ui.icons.Ot3Icons
import dev.avillagran.ot3android.ui.theme.Ot3Radii
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/** One of ot3's icons by name (`ot3:ui/qml/T3Icon.qml`), tinted with an ot3 role. */
@Composable
fun Ot3Icon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Ot3Theme.colors.foreground,
    size: Dp = Ot3Sizes.inlineIcon,
) {
    if (name == "eye") {
        androidx.compose.material3.Text("\uf06e", color = tint,
            fontFamily = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(dev.avillagran.ot3android.R.font.nerd_preview)),
            fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { size.toSp() },
            modifier = modifier.then(if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }))
        return
    }
    Icon(Ot3Icons.painter(name), contentDescription, modifier.size(size), tint = tint)
}

/**
 * ot3's icon button (`ot3:ui/qml/T3IconButton.qml:20-25`): a 20 dp icon in a 48 dp target,
 * a 36 dp pressed shape (radius 6) filled fg @ 0.07, 0.3 opacity when disabled and a 1 dp
 * accent ring under keyboard focus. With a [tooltip], a long press shows it and it is also
 * the content description.
 */
@Composable
fun Ot3IconButton(
    iconName: String,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Ot3Theme.colors.foreground,
    tooltip: String? = null,
    iconRotation: Float = 0f,
) {
    val button = @Composable {
        val colors = Ot3Theme.colors
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val focused by interaction.collectIsFocusedAsState()
        val fill by animateColorAsState(
            if (pressed && enabled) colors.foreground.copy(alpha = PRESSED_FILL) else Color.Transparent,
            Ot3Theme.motion.colorChange(),
            label = "iconButtonFill",
        )
        val shape = RoundedCornerShape(Ot3Radii.iconButton)
        Box(
            modifier
                .size(Ot3Sizes.touchTarget)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(Ot3Sizes.iconButtonPressed)
                    .background(fill, shape)
                    .then(if (focused) Modifier.border(Ot3Sizes.border, colors.accent, shape) else Modifier),
            )
            Ot3Icon(
                iconName,
                contentDescription ?: tooltip,
                Modifier.alpha(if (enabled) 1f else Ot3Sizes.DISABLED_ICON_BUTTON_ALPHA).rotate(iconRotation),
                tint = tint,
                size = Ot3Sizes.icon,
            )
        }
    }
    if (tooltip != null) Ot3Tooltip(tooltip) { button() } else button()
}

/** ot3's pressed / hover fill on icon buttons. */
internal const val PRESSED_FILL = 0.07f
