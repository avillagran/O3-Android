package dev.avillagran.ot3android.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.ui.theme.Ot3Radii
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/**
 * ot3's select (`ot3:ui/qml/T3Select.qml:9-60`): a 40 dp field (radius 7, fill fg @ 0.035,
 * fg @ 0.09 while pressed) with the value in caption text and a 12 dp `chevron-down`. A tap
 * opens a sheet list titled [label] instead of ot3's popup (UI_SPEC rule 1).
 */
@Composable
fun Ot3Select(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "",
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Ot3SelectField(
        text = options.getOrNull(selectedIndex) ?: placeholder,
        label = label,
        onClick = { open = true },
        modifier = modifier,
        enabled = enabled,
    )
    if (open) {
        Ot3Sheet(onDismissRequest = { open = false }, title = label) {
            LazyColumn {
                itemsIndexed(options) { index, option ->
                    Ot3SheetItem(
                        label = option,
                        selected = index == selectedIndex,
                        onClick = {
                            open = false
                            onSelect(index)
                        },
                    )
                }
            }
        }
    }
}

/** The closed select alone: what [Ot3Select] draws before it opens its sheet. */
@Composable
fun Ot3SelectField(
    text: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Ot3Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Ot3Radii.control)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = Ot3Sizes.selectHeight)
            .background(colors.foreground.copy(alpha = if (pressed) PRESSED_ALPHA else FILL_ALPHA), shape)
            .then(if (focused) Modifier.border(Ot3Sizes.border, colors.accent, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.DropdownList, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $text" }
            .padding(start = 9.dp, end = Ot3Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f, fill = false).alpha(if (enabled) 1f else DISABLED_TEXT_ALPHA),
            style = Ot3Theme.typography.material.bodySmall,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Ot3Icon("chevron-down", null, Modifier.padding(start = Ot3Spacing.lg), tint = colors.foreground, size = 12.dp)
    }
}

private const val FILL_ALPHA = 0.035f
private const val PRESSED_ALPHA = 0.09f
private const val DISABLED_TEXT_ALPHA = 0.45f
