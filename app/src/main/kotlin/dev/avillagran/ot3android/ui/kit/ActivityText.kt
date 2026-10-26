package dev.avillagran.ot3android.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.ui.theme.Ot3Motion
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlin.math.exp

/**
 * ot3's live activity text (`ot3:ui/qml/T3ActivityText.qml:18-35`): the text at half
 * opacity with a 72 dp band of full-strength ink sweeping across it every 2.2 s, Gaussian
 * falloff as ot3's `activity.frag`. Inactive text stays at half opacity, as in ot3; while
 * [active] with motion Off the band is gone and the text is drawn whole.
 */
@Composable
fun ActivityText(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    tint: Color = Ot3Theme.colors.foreground,
    style: TextStyle = Ot3Theme.typography.material.bodySmall,
) {
    val animates = active && Ot3Theme.motion.animates
    val time by rememberKitClock(animates)
    Box(modifier) {
        Text(
            text,
            color = tint.copy(alpha = tint.alpha * if (active && !animates) 1f else BASE_ALPHA),
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (animates) {
            val progress = Shimmer.progress(time)
            Text(
                text,
                color = tint,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val band = Ot3Motion.SHIMMER_BAND_DP.dp.toPx()
                        val stops = Shimmer.stops(progress, size.width, band)
                        drawRect(Brush.horizontalGradient(*stops), blendMode = BlendMode.DstIn)
                    },
            )
        }
    }
}

/** The shimmer's band as gradient stops; pure, so tests can check it against ot3's shader. */
internal object Shimmer {
    /** Cycle position 0…1 at [seconds]. */
    fun progress(seconds: Float): Float {
        val cycles = seconds / (Ot3Motion.SHIMMER_CYCLE_MS / 1000f)
        return cycles - kotlin.math.floor(cycles)
    }

    /** ot3's mask at [x] px of a [width]-wide text: `exp(-d² / 0.04)`, d in band widths from the band centre. */
    fun mask(x: Float, progress: Float, width: Float, band: Float): Float {
        val u = (x + band - progress * (width + 2 * band)) / band
        val d = u - 0.5f
        return exp(-(d * d) / 0.04f)
    }

    /** [SAMPLES] stops across the text, the mask as the alpha of black. */
    fun stops(progress: Float, width: Float, band: Float): Array<Pair<Float, Color>> {
        if (width <= 0f) return arrayOf(0f to Color.Transparent, 1f to Color.Transparent)
        return Array(SAMPLES) { i ->
            val f = i / (SAMPLES - 1f)
            f to Color.Black.copy(alpha = mask(f * width, progress, width, band))
        }
    }

    const val SAMPLES = 48
}

private const val BASE_ALPHA = 0.5f
