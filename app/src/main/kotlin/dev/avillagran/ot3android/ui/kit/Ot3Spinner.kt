package dev.avillagran.ot3android.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.ui.theme.Ot3Motion
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * ot3's `WorkIndicator` (`ot3:ui/qml/WorkIndicator.qml:13-53`): a round-capped arc that
 * stretches and contracts over a 1.25 s cycle while it travels around a faint track (tint @
 * 0.22), the whole turning at 225°/s. With motion Off it holds a calm partial ring.
 */
@Composable
fun Ot3Spinner(
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    tint: Color = Ot3Theme.colors.accent,
    active: Boolean = true,
) {
    val time by rememberKitClock(active, still = SpinnerGeometry.STILL_TIME)
    Canvas(
        modifier
            .size(size)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        val side = min(this.size.width, this.size.height)
        val stroke = max(1.4.dp.toPx(), side / 7.5f)
        val radius = max(0f, (side - stroke) / 2)
        val arc = SpinnerGeometry.arc(time)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val box = Size(radius * 2, radius * 2)
        drawArc(tint.copy(alpha = tint.alpha * TRACK_ALPHA), 0f, 360f, false, topLeft, box, style = Stroke(stroke))
        drawArc(tint, arc.start, arc.sweep, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** The spinner's arc at a time, in Compose's degrees (0 = 3 o'clock, clockwise). */
internal object SpinnerGeometry {
    /** Where a still spinner rests: half a cycle, a 253° arc. */
    const val STILL_TIME = 0.625f

    data class Arc(val start: Float, val sweep: Float)

    fun arc(seconds: Float): Arc {
        val cycles = seconds / (Ot3Motion.SPINNER_CYCLE_MS / 1000f)
        val phase = cycles - floor(cycles)
        val head = 270f * ease(min(1f, phase * 1.6f))
        val tail = 270f * ease(max(0f, phase * 1.6f - 0.6f))
        // Each cycle starts where the previous tail stopped.
        val cycle = (floor(cycles) * 270f) % 360f
        val rotation = (seconds * 225f) % 360f
        return Arc(start = (cycle + tail - 90f + rotation) % 360f, sweep = max(14f, head - tail))
    }

    /** InOutCubic. */
    fun ease(v: Float): Float = if (v < 0.5f) 4 * v * v * v else 1 - (-2 * v + 2).pow(3) / 2
}

private const val TRACK_ALPHA = 0.22f
