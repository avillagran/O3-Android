package dev.avillagran.ot3android.ui.sky

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Straight (not premultiplied) sRGB colour with components in 0..1. */
internal data class SkyRgba(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    fun toArray(): FloatArray = floatArrayOf(r, g, b, a)

    companion object {
        val White = SkyRgba(1f, 1f, 1f)

        fun mix(x: SkyRgba, y: SkyRgba, t: Float) = SkyRgba(
            SkyMath.mix(x.r, y.r, t), SkyMath.mix(x.g, y.g, t), SkyMath.mix(x.b, y.b, t), SkyMath.mix(x.a, y.a, t),
        )
    }
}

/**
 * One parallax layer of `starfield.frag` (`ot3:ui/assets/shaders/starfield.frag:88-90`):
 * the `layer()` arguments, the factor applied to the drift and to the scroll, and the
 * weight the layer's light is multiplied by.
 */
internal data class SkyLayer(
    val cell: Float,
    val density: Float,
    val seed: Float,
    val radius: Float,
    val weight: Float,
    val driftFactor: Float,
    val scrollFactor: Float,
)

/** A star of a layer, in the layer's coordinates (dp). */
internal data class SkyStar(
    val centerX: Float,
    val centerY: Float,
    val hash: Float,
    val tint: Float,
    val radius: Float,
    val rate: Float,
    val gain: Float,
)

/** A comet on screen at some clock value, in the header's coordinates (dp). */
internal data class SkyComet(
    val channel: Int,
    val slot: Float,
    /** Progress through its flight, 0..1. */
    val k: Float,
    val dirX: Float,
    val dirY: Float,
    val headX: Float,
    val headY: Float,
    val length: Float,
    val fade: Float,
)

/**
 * The math of ot3's `starfield.frag` and of the clock in `T3Starfield.qml`, in Kotlin.
 * The AGSL port ([STARFIELD_AGSL]) mirrors it line for line; the Canvas fallback draws
 * from it directly, and [shade] is a CPU reference of the whole fragment shader for
 * tests. Float (32-bit) on purpose, like the GPU. Lengths are dp: ot3's px are logical
 * pixels (UI_SPEC 3.3).
 */
internal object SkyMath {
    /** `starfield.frag:88-90`: cells, densities, seeds, radii, weights, drift and scroll factors. */
    val LAYERS = listOf(
        SkyLayer(cell = 29f, density = 0.42f, seed = 1f, radius = 0.55f, weight = 0.55f, driftFactor = 0.30f, scrollFactor = 0.06f),
        SkyLayer(cell = 53f, density = 0.38f, seed = 19f, radius = 0.8f, weight = 0.8f, driftFactor = 0.62f, scrollFactor = 0.12f),
        SkyLayer(cell = 97f, density = 0.32f, seed = 43f, radius = 1.15f, weight = 1f, driftFactor = 1f, scrollFactor = 0.22f),
    )

    /** Drift direction (1, 0.18) (`starfield.frag:84`). */
    const val FLOW_Y = 0.18f

    /** Drift speed idle and while something works, dp/s (`T3Starfield.qml:29,33`). */
    const val IDLE_SPEED = 5f
    const val BUSY_SPEED = 42f

    /** Energy easing, InOutSine (`T3Starfield.qml:28`). */
    const val ENERGY_RAMP_MS = 1600L

    const val COMET_CHANNELS = 4

    /** ot3's header brand row height (`T3Panel.qml:1135`). */
    const val BRAND_HEIGHT = 62f

    /**
     * The panel sky that fills the whole card behind the chat and the sidebar
     * (`T3Panel.qml:842-856`): brightness 0.5, meteor rate 0.3, no nebula, no fade
     * (`T3Starfield.qml:35,49-52`), and a transparent base over the theme surface, so
     * stars are drawn over `Color.popups.background` rather than repainting it.
     */
    const val PANEL_BRIGHTNESS = 0.5f
    const val PANEL_NEBULA = 0f
    const val PANEL_METEOR_RATE = 0.3f

    fun fract(x: Float): Float = x - floor(x)
    fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    fun clamp(x: Float, lo: Float, hi: Float): Float = if (x < lo) lo else if (x > hi) hi else x

    /** GLSL `smoothstep`, including reversed edges as the shader uses them. */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Qt's `Easing.InOutSine`. */
    fun easeInOutSine(t: Float): Float = (-(cos(PI * clamp(t, 0f, 1f)) - 1.0) / 2.0).toFloat()

    /** `hash12` (`starfield.frag:26-30`; `T3Starfield.qml:77-82`). */
    fun hash12(x: Float, y: Float): Float {
        var a = fract(x * 0.1031f)
        var b = fract(y * 0.1031f)
        var c = a
        val d = a * (b + 33.33f) + b * (c + 33.33f) + c * (a + 33.33f)
        a += d; b += d; c += d
        return fract((a + b) * c)
    }

    /** `hash22` (`starfield.frag:32-36`), returned as (x, y). */
    fun hash22(x: Float, y: Float): Pair<Float, Float> {
        var a = fract(x * 0.1031f)
        var b = fract(y * 0.1030f)
        var c = fract(x * 0.0973f)
        val d = a * (b + 33.33f) + b * (c + 33.33f) + c * (a + 33.33f)
        a += d; b += d; c += d
        return fract((a + b) * c) to fract((a + c) * b)
    }

    // ---- Stars (`starfield.frag:38-53`) ----

    /** The cell's tint hash, computed whether or not the cell holds a star. */
    fun cellTint(layer: SkyLayer, idX: Float, idY: Float): Float =
        hash12(idX + layer.seed + 5.3f, idY + layer.seed + 5.3f)

    /** The star of cell ([idX], [idY]), or null when the cell is empty. */
    fun star(layer: SkyLayer, idX: Float, idY: Float): SkyStar? {
        val s = layer.seed
        val h = hash12(idX + s, idY + s)
        if (h > layer.density) return null
        val (px, py) = hash22(idX + s * 1.7f, idY + s * 1.7f)
        return SkyStar(
            centerX = (idX + 0.2f + 0.6f * px) * layer.cell,
            centerY = (idY + 0.2f + 0.6f * py) * layer.cell,
            hash = h,
            tint = cellTint(layer, idX, idY),
            radius = layer.radius * mix(0.55f, 1.25f, hash12(idX + s + 3.1f, idY + s + 3.1f)),
            rate = 0.6f + 2.2f * hash12(idX + s + 7f, idY + s + 7f),
            gain = mix(0.45f, 1f, hash12(idX + s + 11f, idY + s + 11f)),
        )
    }

    /** `0.55 + 0.45 · sin(t · rate + h · 61)` (`starfield.frag:49`). */
    fun twinkle(clock: Float, rate: Float, hash: Float): Float = 0.55f + 0.45f * sin(clock * rate + hash * 61f)

    /** Core plus halo of a star of radius [r] at distance [d] (`starfield.frag:50-51`). */
    fun starProfile(d: Float, r: Float): Float =
        smoothstep(r + 0.9f, r * 0.2f, d) + exp(-d * d / (r * r * 7f)) * 0.3f

    /** `layer()` at layer point ([px], [py]): its light and the cell's tint. */
    fun layerSample(layer: SkyLayer, px: Float, py: Float, clock: Float): Pair<Float, Float> {
        val idX = floor(px / layer.cell)
        val idY = floor(py / layer.cell)
        val tint = cellTint(layer, idX, idY)
        val star = star(layer, idX, idY) ?: return 0f to tint
        val d = hypot(px - star.centerX, py - star.centerY)
        return starProfile(d, star.radius) * twinkle(clock, star.rate, star.hash) * star.gain to tint
    }

    /** Offset of a layer's coordinates from the header's (`starfield.frag:83-90`). */
    fun layerOffsetX(layer: SkyLayer, u: SkyUniforms): Float = u.originX + u.drift * layer.driftFactor
    fun layerOffsetY(layer: SkyLayer, u: SkyUniforms): Float =
        u.originY + u.drift * FLOW_Y * layer.driftFactor + u.scroll * layer.scrollFactor

    // ---- Comets (`starfield.frag:55-79`) ----

    /** Channel period, 2.1 + 1.37·n s. */
    fun cometPeriod(channel: Int): Float = 2.1f + channel * 1.37f

    /** Tail length, clamp(0.22 × diagonal, 60, 240) dp. */
    fun tailLength(width: Float, height: Float): Float = clamp(hypot(width, height) * 0.22f, 60f, 240f)

    /** Whether channel [channel] fires in roll [slot]: its hash against the meteor level. */
    fun cometFires(channel: Int, slot: Float, meteors: Float): Boolean = hash12(slot, channel * 17f) <= meteors

    /**
     * The comet of [channel] at [clock] in a [width] × [height] dp sky, or null. The shader
     * skips comets entirely at a level ≤ 0.001 (`starfield.frag:94`), so energy 0 means none.
     */
    fun comet(channel: Int, clock: Float, meteors: Float, width: Float, height: Float): SkyComet? {
        if (meteors <= 0.001f) return null
        val ch = channel.toFloat()
        val period = cometPeriod(channel)
        val slot = floor(clock / period + ch * 0.31f)
        val t = fract(clock / period + ch * 0.31f) * period
        val keyY = ch * 17f
        if (!cometFires(channel, slot, meteors)) return null
        val duration = 0.75f + 0.5f * hash12(slot + 2f, keyY + 2f)
        if (t > duration) return null
        val k = t / duration
        val dy = 0.28f + 0.35f * hash12(slot + 4f, keyY + 4f)
        val dl = sqrt(1f + dy * dy)
        val dirX = -1f / dl
        val dirY = dy / dl
        val span = hypot(width, height)
        val startX = width * (0.35f + 0.75f * hash12(slot + 6f, keyY + 6f))
        val startY = height * (-0.15f + 0.6f * hash12(slot + 8f, keyY + 8f))
        return SkyComet(
            channel = channel,
            slot = slot,
            k = k,
            dirX = dirX,
            dirY = dirY,
            headX = startX + dirX * span * 0.9f * k,
            headY = startY + dirY * span * 0.9f * k,
            length = tailLength(width, height),
            fade = smoothstep(0f, 0.12f, k) * smoothstep(1f, 0.55f, k),
        )
    }

    /** `meteor()` at header point ([qx], [qy]) for a comet on screen. */
    fun cometSample(c: SkyComet, qx: Float, qy: Float): Float {
        val rx = qx - c.headX
        val ry = qy - c.headY
        val along = -(rx * c.dirX + ry * c.dirY)
        val perp = abs(rx * -c.dirY + ry * c.dirX)
        val tail = clamp(1f - along / c.length, 0f, 1f)
        val streak = if (along > -1.5f) smoothstep(0.6f + 1.1f * tail, 0f, perp) * tail * tail else 0f
        val glow = exp(-(rx * rx + ry * ry) / 10f)
        return (streak + glow) * c.fade
    }

    /**
     * ot3's schedule check, `meteorLive` (`T3Starfield.qml:83-92`): whether a comet is on
     * screen at [clock], with its margins for GPU rounding. Drives the tick pace only.
     */
    fun meteorLive(clock: Float, level: Float): Boolean {
        for (channel in 0 until COMET_CHANNELS) {
            val period = cometPeriod(channel)
            val phase = clock / period + channel * 0.31f
            val slot = floor(phase)
            val t = (phase - slot) * period
            if (hash12(slot, channel * 17f) > level + 0.02f) continue
            if (t <= 0.75f + 0.5f * hash12(slot + 2f, channel * 17f + 2f) + 0.05f) return true
        }
        return false
    }

    /**
     * The `meteors` uniform (`T3Starfield.qml:47-48`): Full shows comets everywhere, Light
     * only in a header sky, Off never; scaled by [energy], which is 0 while idle.
     */
    fun meteorLevel(motion: SkyMotion, header: Boolean, meteorRate: Float, energy: Float, meteorScale: Float = 1f): Float =
        if (motion == SkyMotion.Full || header && motion == SkyMotion.Light) min(1f, meteorRate * meteorScale) * energy else 0f

    // ---- Colours (`T3Panel.qml:1125-1126`, `T3Starfield.qml:55-63`) ----

    /** Rec.709 luminance of the encoded components above 0.5 (`T3Starfield.qml:58`). */
    fun isLightSky(surface: SkyRgba): Boolean = 0.2126f * surface.r + 0.7152f * surface.g + 0.0722f * surface.b > 0.5f

    fun inkGain(lightSky: Boolean): Float = if (lightSky) 1.7f else 1f

    /**
     * Header base: light sky = `Qt.tint(surface, fg @ 0.06)`; dark sky =
     * `Qt.darker(surface, 2.4)`, which divides HSV value by 2.4 and so every channel.
     */
    fun baseColor(background: SkyRgba, foreground: SkyRgba, lightSky: Boolean): SkyRgba =
        if (lightSky) {
            SkyRgba(mix(background.r, foreground.r, 0.06f), mix(background.g, foreground.g, 0.06f), mix(background.b, foreground.b, 0.06f), background.a)
        } else {
            SkyRgba(background.r / 2.4f, background.g / 2.4f, background.b / 2.4f, background.a)
        }

    /**
     * The panel's base: transparent, so the theme's own surface shows through
     * (`T3Starfield.qml:49`, the panel instance leaves `base` unset), or the opaque theme
     * background where the sky has to cover what is behind it (the phone's sidebar drawer).
     */
    fun panelBase(background: SkyRgba, opaque: Boolean): SkyRgba =
        if (opaque) background else SkyRgba(background.r, background.g, background.b, 0f)

    /** Meteor ink: foreground on light skies, white on dark ones. */
    fun meteorInk(foreground: SkyRgba, lightSky: Boolean): SkyRgba = if (lightSky) foreground else SkyRgba.White

    /** `fade = 1 − brand.height × 0.85 / height` (`T3Panel.qml:1127`). */
    fun fade(height: Float, brandHeight: Float): Float = if (height > 0f) 1f - brandHeight * 0.85f / height else 0f

    /** The soft bottom edge (`starfield.frag:97`), [y] in 0..1 of the height. */
    fun edge(fade: Float, y: Float): Float = if (fade > 0f) 1f - smoothstep(1f - fade, 1f, y) else 1f

    /** Every uniform of one frame. Sizes in dp; [pixelDensity] converts fragment pixels to dp. */
    fun uniforms(
        palette: SkyPalette,
        motion: SkyMotion,
        clock: Float,
        drift: Float,
        energy: Float,
        widthDp: Float,
        heightDp: Float,
        pixelDensity: Float,
        brandHeightDp: Float = BRAND_HEIGHT,
        header: Boolean = true,
        fade: Float = fade(heightDp, brandHeightDp),
    ): SkyUniforms {
        val bg = palette.background.toSkyRgba()
        val fg = palette.foreground.toSkyRgba()
        return SkyUniforms(
            pixelDensity = pixelDensity,
            opacity = 1f,
            drift = drift,
            clock = clock,
            meteors = meteorLevel(motion, header, palette.meteorRate, energy),
            brightness = palette.brightness,
            nebula = palette.nebula,
            scroll = 0f,
            fade = fade,
            width = widthDp,
            height = heightDp,
            originX = 0f,
            originY = 0f,
            star = fg,
            accent = palette.accent.toSkyRgba(),
            base = baseColor(bg, fg, palette.lightSky),
            meteor = meteorInk(fg, palette.lightSky),
            ink = palette.ink,
        )
    }

    /**
     * CPU reference of `main()` (`starfield.frag:81-117`) at header point ([qx], [qy]) dp:
     * premultiplied RGBA. Used by tests and to render reference images; never per frame.
     */
    fun shade(u: SkyUniforms, qx: Float, qy: Float): FloatArray {
        val uvx = qx / u.width
        val uvy = qy / u.height
        var s = 0f
        var tintSum = 0f
        for (layer in LAYERS) {
            val (v, t) = layerSample(layer, qx + layerOffsetX(layer, u), qy + layerOffsetY(layer, u), u.clock)
            s += v * layer.weight
            tintSum += t
        }
        val tint = tintSum / 3f
        val starColor = SkyRgba.mix(u.star, u.accent, smoothstep(0.55f, 1f, tint) * 0.6f)
        var m = 0f
        if (u.meteors > 0.001f) {
            for (ch in 0 until COMET_CHANNELS) {
                val c = comet(ch, u.clock, u.meteors, u.width, u.height) ?: continue
                m += cometSample(c, qx, qy)
            }
        }
        val edge = edge(u.fade, uvy)
        val light = clamp(s * u.brightness * u.ink, 0f, 1f) * edge
        val shoot = clamp(m * u.ink, 0f, 1f) * edge
        val cloud = u.nebula * (0.55f * (1f - uvx) + 0.25f * (1f - uvy)) * (0.8f + 0.2f * sin(u.clock * 0.25f + uvx * 3f))
        val sky = SkyRgba.mix(u.base, u.accent, clamp(cloud, 0f, 1f) * 0.35f)
        val skyA = u.base.a * edge
        var r = sky.r * skyA
        var g = sky.g * skyA
        var b = sky.b * skyA
        var a = skyA
        r = r * (1f - light) + starColor.r * light
        g = g * (1f - light) + starColor.g * light
        b = b * (1f - light) + starColor.b * light
        a += light * (1f - a)
        val meteorColor = SkyRgba.mix(u.meteor, u.accent, 0.25f)
        r = r * (1f - shoot) + meteorColor.r * shoot
        g = g * (1f - shoot) + meteorColor.g * shoot
        b = b * (1f - shoot) + meteorColor.b * shoot
        a += shoot * (1f - a)
        return floatArrayOf(r * u.opacity, g * u.opacity, b * u.opacity, a * u.opacity)
    }
}
