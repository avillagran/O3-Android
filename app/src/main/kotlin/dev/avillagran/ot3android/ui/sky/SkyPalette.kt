package dev.avillagran.ot3android.ui.sky

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * What the sky needs from a theme: the three base colours and the theme's `sky` object
 * (`docs/ui/omarchy-themes.md`). Home maps its theme onto this, so the sky does not depend
 * on the theme classes.
 *
 * - [lightSky]: Rec.709 luminance of [background] above 0.5 (`ot3:ui/qml/T3Starfield.qml:58`).
 * - [brightness]: 0.9 on light skies, 1.25 on dark ones (`ot3:ui/qml/T3Panel.qml:1126`).
 * - [nebula] 0.55 and [meteorRate] 0.85, the header's values (`ot3:ui/qml/T3Panel.qml:1126`).
 * - [ink]: 1.7 on light skies, 1 on dark ones (`ot3:ui/qml/T3Starfield.qml:63`).
 */
@Immutable
data class SkyPalette(
    val background: Color,
    val foreground: Color,
    val accent: Color,
    val lightSky: Boolean,
    val brightness: Float,
    val nebula: Float,
    val meteorRate: Float,
    val ink: Float,
) {
    companion object {
        /** The palette ot3 would derive for a header sky over [background]. */
        fun derive(background: Color, foreground: Color, accent: Color): SkyPalette {
            val light = SkyMath.isLightSky(background.toSkyRgba())
            return SkyPalette(
                background = background,
                foreground = foreground,
                accent = accent,
                lightSky = light,
                brightness = if (light) 0.9f else 1.25f,
                nebula = 0.55f,
                meteorRate = 0.85f,
                ink = SkyMath.inkGain(light),
            )
        }
    }
}

/**
 * Animation level of the sky, ot3's "Animated starfield" preference without Auto
 * (`ot3:ui/qml/T3Preferences.qml:260`). Auto is [Full] plus `onAutoDegrade` on
 * [SkyHeader]: the header drops to [Light] by itself after 90 slow ticks and reports it.
 */
enum class SkyMotion {
    /** Stars, drift, twinkle and comets while something works. */
    Full,

    /**
     * ot3's light mode: comets stay only in the header sky
     * (`ot3:ui/qml/T3Starfield.qml:47-48`), which is the only sky on Android, and the sky
     * ticks at 25 fps at most (`ot3:ui/qml/T3Starfield.qml:99`).
     */
    Light,

    /** One static frame: the clock stops and no comet is drawn. */
    Off,
    ;

    /**
     * Applies the platform rules of UI_SPEC 3.4: animator duration scale 0 → [Off];
     * battery saver → [Light] at most. Never raises the level.
     */
    fun limitedBy(animatorScale: Float, powerSave: Boolean): SkyMotion = when {
        this == Off || animatorScale == 0f -> Off
        powerSave -> Light
        else -> this
    }
}

internal fun Color.toSkyRgba(): SkyRgba = SkyRgba(red, green, blue, alpha)
