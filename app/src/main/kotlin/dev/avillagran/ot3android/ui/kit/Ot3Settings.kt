package dev.avillagran.ot3android.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/**
 * ot3's preference section (`ot3:ui/qml/T3PreferenceSection.qml`): a 14 dp icon in fg @ 0.55
 * and the title in caption text, fg @ 0.65, then a card (radius 11, fill fg @ 0.025 =
 * `changesCard`, 1 dp border fg @ 0.085 ≈ `divider`) holding [Ot3SettingsRow]s.
 */
@Composable
fun Ot3SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    iconName: String = "settings",
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Ot3Theme.colors
    val shape = RoundedCornerShape(GROUP_RADIUS)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            Modifier.padding(horizontal = Ot3Spacing.xxl).semantics(mergeDescendants = true) { heading() },
            horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ot3Icon(iconName, null, tint = colors.foreground.copy(alpha = 0.55f), size = 14.dp)
            Text(title, style = Ot3Theme.typography.material.bodySmall, color = colors.foreground.copy(alpha = 0.65f))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.changesCard, shape)
                .border(Ot3Sizes.border, colors.divider, shape),
            content = content,
        )
    }
}

/**
 * ot3's preference field (`ot3:ui/qml/T3PreferenceField.qml`): [label] in subtitle text
 * (15 sp), an optional [description] in caption `dim`, the [trailing] control at the end,
 * padding 16 × 14 and a hairline above when [divided]. With [onClick] the whole row is the
 * target (a switch row toggles from anywhere on it).
 */
@Composable
fun Ot3SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    divided: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    role: Role? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Ot3Theme.colors
    Column(modifier.fillMaxWidth()) {
        if (divided) Box(Modifier.fillMaxWidth().height(Ot3Sizes.border).background(colors.divider))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Ot3Sizes.touchTarget)
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = role, onClick = onClick) else Modifier)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 16.dp, vertical = Ot3Spacing.xxxl)
                .alpha(if (enabled) 1f else Ot3Sizes.DISABLED_CONTROL_ALPHA),
            horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.xxl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Ot3Spacing.sm)) {
                Text(label, style = Ot3Theme.typography.material.bodyLarge, color = colors.foreground)
                if (description != null) {
                    Text(description, style = Ot3Theme.typography.material.bodySmall, color = colors.dim)
                }
            }
            trailing?.invoke()
        }
    }
}

private val GROUP_RADIUS = 11.dp
