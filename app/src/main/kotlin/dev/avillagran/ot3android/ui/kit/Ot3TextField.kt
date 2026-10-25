package dev.avillagran.ot3android.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.ui.theme.Ot3Radii
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/**
 * ot3's text field (`ot3:ui/qml/T3TextField.qml:12-23`): `field` fill, 1 dp `fieldBorder`
 * turning accent on focus over 140 ms, radius 6, 48 dp high, body text (14 sp on Android so
 * the keyboard never edits caption-size text), placeholder fg @ 0.5, selection accent @ 0.35.
 * [leadingIcon] and [trailing] sit inside the frame (search icon, clear button).
 */
@Composable
fun Ot3TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = placeholder,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    leadingIcon: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val colors = Ot3Theme.colors
    val focused by interactionSource.collectIsFocusedAsState()
    val border by animateColorAsState(
        if (focused) colors.accent else colors.fieldBorder,
        Ot3Theme.motion.fieldBorder(),
        label = "fieldBorder",
    )
    val shape = RoundedCornerShape(Ot3Radii.field)
    val style = Ot3Theme.typography.material.bodyMedium.copy(color = colors.foreground)
    val selection = remember(colors.accent, colors.foreground) {
        TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.accent.copy(alpha = SELECTION_ALPHA))
    }
    CompositionLocalProvider(LocalTextSelectionColors provides selection) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .alpha(if (enabled) 1f else Ot3Sizes.DISABLED_CONTROL_ALPHA)
                .semantics { if (label != null) contentDescription = label },
            enabled = enabled,
            singleLine = singleLine,
            textStyle = style,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            decorationBox = { inner ->
                Row(
                    Modifier
                        .heightIn(min = Ot3Sizes.fieldHeight)
                        .background(colors.field, shape)
                        .border(Ot3Sizes.border, border, shape)
                        .padding(start = if (leadingIcon != null) Ot3Spacing.xxl else Ot3Spacing.xl, end = if (trailing != null) 0.dp else Ot3Spacing.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        Ot3Icon(leadingIcon, null, tint = colors.dim, size = Ot3Sizes.icon)
                        Box(Modifier.width(Ot3Spacing.lg))
                    }
                    Box(Modifier.weight(1f).padding(vertical = Ot3Spacing.md), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                style = style,
                                color = colors.foreground.copy(alpha = PLACEHOLDER_ALPHA),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                    trailing?.invoke()
                }
            },
        )
    }
}

private const val PLACEHOLDER_ALPHA = 0.5f
private const val SELECTION_ALPHA = 0.35f
