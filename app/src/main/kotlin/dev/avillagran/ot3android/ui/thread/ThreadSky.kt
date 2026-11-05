package dev.avillagran.ot3android.ui.thread

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.avillagran.ot3android.ui.sky.SkyHeader
import dev.avillagran.ot3android.ui.sky.SkyMotion
import dev.avillagran.ot3android.ui.sky.SkyPalette
import dev.avillagran.ot3android.ui.theme.Ot3MotionLevel
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/**
 * Desktop's panel sky behind a thread (`ot3:ui/qml/T3Panel.qml:1036`): brightness 0.5, no
 * nebula, meteor rate 0.3. One still frame while the thread is idle; while it works the
 * sky drifts and shows comets, eased in and out by the sky clock.
 */
@Composable
internal fun ThreadSky(working: Boolean, modifier: Modifier = Modifier) {
    val colors = Ot3Theme.colors
    val level = Ot3Theme.motion.level
    val motion = when {
        !working || level == Ot3MotionLevel.OFF -> SkyMotion.Off
        level == Ot3MotionLevel.LIGHT -> SkyMotion.Light
        else -> SkyMotion.Full
    }
    SkyHeader(
        palette = SkyPalette(colors.background, colors.foreground, colors.accent, colors.sky.lightSky,
            brightness = 0.5f, nebula = 0f, meteorRate = 0.3f, ink = colors.sky.ink),
        motion = motion,
        energy = if (working) 1f else 0f,
        panel = true,
        modifier = modifier.testTag(if (working) "thread_sky_working" else "thread_sky_idle"),
    )
}
