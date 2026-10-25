package dev.avillagran.ot3android.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import dev.avillagran.ot3android.ui.theme.LocalOt3Motion

/**
 * Seconds since [running] became true, advanced every frame while it is true and motion is
 * not Off; otherwise it holds [still]. One clock per animated element, like ot3's `T3Tick`.
 */
@Composable
internal fun rememberKitClock(running: Boolean, still: Float = 0f): State<Float> {
    val animates = running && LocalOt3Motion.current.animates
    val time = remember { mutableFloatStateOf(still) }
    LaunchedEffect(animates) {
        if (!animates) {
            time.floatValue = still
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> time.floatValue = (now - start) / 1_000_000_000f }
        }
    }
    return time
}

/** ot3's working icon turns at 112.5°/s on the sky clock (`ot3:ui/qml/T3ThreadRow.qml:277`). */
internal const val STATUS_SPIN_DEGREES_PER_SECOND = 112.5f
