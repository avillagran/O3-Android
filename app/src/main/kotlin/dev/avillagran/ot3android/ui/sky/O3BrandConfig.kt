package dev.avillagran.ot3android.ui.sky

import androidx.compose.runtime.Immutable
import kotlin.math.*

/** Plain values for P02 to persist. Defaults match Desktop T3BrandMark/T3Motion. */
@Immutable
data class O3BrandConfig(
    val glyph: O3BrandGlyph = O3BrandGlyph.O,
    val sober: Boolean = true,
    val reducedMotion: Boolean = false,
    val running: Boolean = true,
    val pace: Float = 1f,
) {
    init { require(pace.isFinite() && pace > 0f) }
    internal val effectivePace: Float get() = max(0.3f, pace)
    internal fun ms(value: Int): Long = (value * effectivePace).roundToLong()
    internal val morphMs: Long get() = ms(if (glyph == O3BrandGlyph.OMARCHY) 1400 else 1100)
}

enum class O3BrandGlyph { O, OMARCHY }

/** Deterministic scene time, also used by screenshot fixtures (no test-only renderer). */
internal data class O3BrandFrame(val scene: Int = -1, val time: Float = 0f, val morph: Float = 0f, val spelled: Boolean = false)

internal fun brandFrame(elapsedMs: Long, config: O3BrandConfig): O3BrandFrame {
    if (config.reducedMotion) return O3BrandFrame(spelled = true)
    if (!config.running || elapsedMs < 2400) return O3BrandFrame()
    val enter = config.ms(7 * 95 + 900) + 3600
    val leave = config.ms(7 * 70 + 560)
    val scenes = listOf(config.morphMs + 120, enter, leave, config.morphMs, 7000L)
    var t = (elapsedMs - 2400) % scenes.sum()
    for ((scene, duration) in scenes.withIndex()) {
        if (t < duration) {
            val local = t / config.effectivePace
            val progress = ((local - 170) / if (config.glyph == O3BrandGlyph.OMARCHY) 920f else 620f).coerceIn(0f, 1f)
            val m = if (config.glyph == O3BrandGlyph.OMARCHY) progress else cubic(progress)
            return O3BrandFrame(scene, local, when(scene) { 0 -> m; 1, 2 -> 1f; 3 -> 1f - m; else -> 0f })
        }
        t -= duration
    }
    return O3BrandFrame()
}

internal fun cubic(t: Float): Float = if (t < .5f) 4*t*t*t else 1 - (-2*t+2).pow(3)/2
internal fun outQuad(t: Float): Float = 1-(1-t)*(1-t)
internal fun outBack(t: Float, s: Float): Float { val u=t-1; return u*u*((s+1)*u+s)+1 }
internal fun bounce(t: Float): Float = when {
    t < 1/2.75f -> 7.5625f*t*t
    t < 2/2.75f -> { val u=t-1.5f/2.75f; 7.5625f*u*u+.75f }
    t < 2.5f/2.75f -> { val u=t-2.25f/2.75f; 7.5625f*u*u+.9375f }
    else -> { val u=t-2.625f/2.75f; 7.5625f*u*u+.984375f }
}

/** P02 preferences and the effective system motion policy feed the production header. */
internal fun homeBrandConfig(settings: dev.avillagran.ot3android.core.data.prefs.AppearanceSettings, motionOff: Boolean) = O3BrandConfig(
    glyph = if (settings.brandGlyph == "omarchy") O3BrandGlyph.OMARCHY else O3BrandGlyph.O,
    sober = settings.sidebarStyle != "vivid",
    reducedMotion = !settings.animationsEnabled || motionOff,
)
