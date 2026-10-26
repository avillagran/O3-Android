package dev.avillagran.ot3android.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/**
 * ot3's switch (`ot3:ui/qml/T3Switch.qml:11-17`): a 36 × 20 dp pill, accent when on and
 * fg @ 0.15 (`switchOff`) when off, a 14 dp knob 3 dp from the edge (background on accent,
 * foreground on the off track) sliding over 120 ms OutCubic, 0.4 opacity when disabled.
 * With [onCheckedChange] it is its own 48 dp target; without, a settings row toggles it.
 */
@Composable
fun Ot3Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = Ot3Theme.colors
    val motion = Ot3Theme.motion
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val track by animateColorAsState(if (checked) colors.accent else colors.switchOff, motion.colorChange(), label = "switchTrack")
    val knob by animateColorAsState(if (checked) colors.background else colors.foreground, motion.colorChange(), label = "switchKnob")
    val inset = (Ot3Sizes.switchHeight - Ot3Sizes.switchKnob) / 2
    val x by animateDpAsState(if (checked) Ot3Sizes.switchWidth - Ot3Sizes.switchKnob - inset else inset, motion.switchKnob(), label = "switchX")
    val on = stringResource(R.string.ot3_kit_on)
    val off = stringResource(R.string.ot3_kit_off)
    val toggle = if (onCheckedChange != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .toggleable(checked, interaction, null, enabled, Role.Switch) { onCheckedChange(it) }
    } else {
        Modifier
    }
    Box(
        modifier
            .then(toggle)
            .semantics {
                stateDescription = if (checked) on else off
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Ot3Sizes.switchWidth, Ot3Sizes.switchHeight)
                .alpha(if (enabled) 1f else Ot3Sizes.DISABLED_CONTROL_ALPHA)
                .background(track, CircleShape)
                .then(if (focused) Modifier.border(Ot3Sizes.border, colors.foreground, CircleShape) else Modifier),
        ) {
            Box(
                Modifier
                    .offset { IntOffset(x.roundToPx(), inset.roundToPx()) }
                    .size(Ot3Sizes.switchKnob)
                    .background(knob, CircleShape),
            )
        }
    }
}
