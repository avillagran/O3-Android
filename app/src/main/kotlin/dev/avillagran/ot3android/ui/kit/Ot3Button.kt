package dev.avillagran.ot3android.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.avillagran.ot3android.ui.theme.Ot3Radii
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/** ot3's `T3PickerButton` looks (`ot3:ui/qml/T3PickerButton.qml:10-14`). */
enum class Ot3ButtonStyle {
    /** Accent fill, text in `onAccent`. */
    PRIMARY,

    /** No fill, foreground text, fg @ 0.08 when pressed. */
    SECONDARY,

    /** A 1 dp fg @ 0.22 outline. */
    OUTLINE,
}

/**
 * ot3's button: 40 dp drawn in a 48 dp target, padding 15, radius 7, body text, 0.4 opacity
 * when disabled. [destructive] turns the text (and the outline) `failed`, or the fill on
 * [Ot3ButtonStyle.PRIMARY], for confirmations like Delete (`ot3:ui/qml/T3ThreadRow.qml:424`).
 */
@Composable
fun Ot3Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: Ot3ButtonStyle = Ot3ButtonStyle.PRIMARY,
    enabled: Boolean = true,
    destructive: Boolean = false,
    iconName: String? = null,
) {
    val colors = Ot3Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tone = if (destructive) colors.failed else colors.accent
    val fillTarget = when (style) {
        // Qt.lighter(accent, 1.12) on hover: a touch lighter while pressed.
        Ot3ButtonStyle.PRIMARY -> if (pressed && enabled) lerp(tone, Color.White, PRIMARY_PRESSED_LIGHTEN) else tone
        else -> if (pressed && enabled) colors.foreground.copy(alpha = SECONDARY_PRESSED_FILL) else Color.Transparent
    }
    val fill by animateColorAsState(fillTarget, Ot3Theme.motion.colorChange(), label = "buttonFill")
    val content = when {
        style == Ot3ButtonStyle.PRIMARY -> colors.onAccent
        destructive -> colors.failed
        else -> colors.foreground
    }
    val border = when {
        focused -> colors.foreground
        style == Ot3ButtonStyle.OUTLINE && destructive -> colors.failed
        style == Ot3ButtonStyle.OUTLINE -> colors.foreground.copy(alpha = OUTLINE_ALPHA)
        else -> null
    }
    val shape = RoundedCornerShape(Ot3Radii.control)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else Ot3Sizes.DISABLED_CONTROL_ALPHA)
            .heightIn(min = Ot3Sizes.buttonHeight)
            .background(fill, shape)
            .then(if (border != null) Modifier.border(Ot3Sizes.border, border, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Ot3Sizes.buttonPaddingHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.md, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconName != null) Ot3Icon(iconName, null, tint = content)
        Text(text, color = content, style = Ot3Theme.typography.material.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val PRIMARY_PRESSED_LIGHTEN = 0.12f
private const val SECONDARY_PRESSED_FILL = 0.08f
private const val OUTLINE_ALPHA = 0.22f
