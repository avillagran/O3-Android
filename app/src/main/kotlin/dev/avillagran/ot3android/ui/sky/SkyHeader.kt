package dev.avillagran.ot3android.ui.sky

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.core.data.prefs.AnimationPreference
import dev.avillagran.ot3android.ui.theme.Ot3Colors
import dev.avillagran.ot3android.ui.theme.Ot3MotionLevel
import dev.avillagran.ot3android.ui.theme.Ot3MotionPolicy

object SkyDefaults {
    /** ot3's brand row height, the part of the header the sky stays solid behind (`T3Panel.qml:1135`). */
    val BrandHeight: Dp = SkyMath.BRAND_HEIGHT.dp
}

/** The theme's motion policy as the sky's level (UI_SPEC 3.4). */
val Ot3MotionPolicy.skyMotion: SkyMotion
    get() = when (level) {
        Ot3MotionLevel.FULL -> SkyMotion.Full
        Ot3MotionLevel.LIGHT -> SkyMotion.Light
        Ot3MotionLevel.OFF -> SkyMotion.Off
    }

/** Whether the preference is Auto, which may drop the sky to Light on its own (3.4). */
val Ot3MotionPolicy.skyAuto: Boolean
    get() = preference == AnimationPreference.AUTO

/**
 * ot3's sidebar header sky (UI_SPEC 3.5): the procedural starfield behind [content],
 * solid behind the brand row and fading out below it. AGSL on API 33+, Canvas below. The
 * clock is the screen's [SkyField], so the band dissolves into the panel sky behind it
 * (`ot3:ui/qml/T3Panel.qml:1121-1128`).
 *
 * @param energy 0 idle … 1 something works; drift eases between 5 and 42 dp/s over
 *   1 600 ms and comets appear only above 0.
 * @param brandHeight height of the brand row; the sky fades over the rest
 *   (`fade = 1 − brandHeight × 0.85 / height`).
 * @param onAutoDegrade non-null while the header watches its ticks: after 90 slow ones
 *   while working it drops to [SkyMotion.Light] for the rest of the screen's life and
 *   calls this once so the preference can remember it.
 */
@Composable
fun SkyHeader(
    palette: SkyPalette,
    motion: SkyMotion,
    energy: Float,
    modifier: Modifier = Modifier,
    brandHeight: Dp = SkyDefaults.BrandHeight,
    forceCanvas: Boolean = false,
    onAutoDegrade: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val field = rememberSkyField(motion, onAutoDegrade = onAutoDegrade)
    SkyWorking(field, energy > 0f)
    // The header is the brightest of ot3's skies, so it sets the pace for comets.
    SideEffect { field.clock.meteorReach = palette.meteorRate }
    SkySurface(
        field = field,
        palette = palette,
        header = true,
        brandHeightDp = brandHeight.value,
        opaqueBase = true,
        forceCanvas = forceCanvas,
        modifier = modifier,
        content = content,
    )
}

/**
 * ot3's panel sky: the starfield that fills the whole card behind the chat and the
 * sidebar (`ot3:ui/qml/T3Panel.qml:842-856`), at brightness 0.5 and meteor rate 0.3, with
 * no nebula and no fade, over the theme surface. Content on top must not paint an opaque
 * background of its own.
 *
 * @param opaque draws the theme's background under the stars, for a surface that has to
 *   hide what is behind it (the phone's sidebar drawer) while keeping the same field.
 */
@Composable
fun SkyBackdrop(
    palette: SkyPalette,
    motion: SkyMotion,
    modifier: Modifier = Modifier,
    opaque: Boolean = false,
    forceCanvas: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val field = rememberSkyField(motion)
    SkySurface(
        field = field,
        palette = palette.panel(),
        header = false,
        brandHeightDp = 0f,
        opaqueBase = opaque,
        forceCanvas = forceCanvas,
        modifier = modifier,
        content = content,
    )
}

/**
 * One region of the field. [header] selects ot3's two looks: the header's darker, brighter
 * band that fades out below the brand row, or the panel's dim field with no fade.
 */
@Composable
private fun SkySurface(
    field: SkyField,
    palette: SkyPalette,
    header: Boolean,
    brandHeightDp: Float,
    opaqueBase: Boolean,
    forceCanvas: Boolean,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // Typed as Shader so nothing below API 33 touches RuntimeShader.
    val shaderFailure = remember { booleanArrayOf(false) }
    val shader: Shader? = if (!forceCanvas && !shaderFailure[0] && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { runCatching { StarfieldShaders.create() }.getOrNull() }
    } else {
        null
    }
    Box(
        modifier.drawWithCache {
            val brush = shader?.let { ShaderBrush(it) }
            val brand = brandHeightDp
            onDrawBehind {
                // Reading the field's tick redraws on every tick.
                if (field.tick < 0 || size.width <= 0f || size.height <= 0f) return@onDrawBehind
                val height = size.height / density
                val u = SkyMath.uniforms(
                    palette = palette,
                    motion = field.motion,
                    clock = field.clock.clock,
                    drift = field.clock.drift,
                    energy = field.clock.energy,
                    widthDp = size.width / density,
                    heightDp = height,
                    pixelDensity = density,
                    brandHeightDp = brand,
                    header = header,
                    fade = if (header) SkyMath.fade(height, brand) else 0f,
                ).let { if (header) it else it.copy(base = SkyMath.panelBase(palette.background.toSkyRgba(), opaqueBase)) }
                // A driver that cannot take the uniforms or draw the shader (Mali on some
                // Android 16 builds) keeps the whole region on the Canvas fallback instead
                // of leaving a blank sky; nothing is drawn twice.
                val drawn = shader != null && brush != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    runCatching { StarfieldShaders.apply(shader, u); drawRect(brush) }.isSuccess
                if (!drawn) {
                    if (shader != null) shaderFailure[0] = true
                    drawSkyCanvas(SkyCanvasPlan.build(u))
                }
            }
        },
        content = content,
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object StarfieldShaders {
    fun create(): Shader = RuntimeShader(STARFIELD_AGSL)

    fun apply(shader: Shader, u: SkyUniforms) {
        val runtime = shader as RuntimeShader
        u.forEachUniform { name, values -> runtime.setFloatUniform(name, values) }
    }
}

/** Draws a [SkyCanvasPlan] (dp) into this scope (px). */
internal fun DrawScope.drawSkyCanvas(plan: SkyCanvasPlan) {
    val base = plan.base.toColor()
    // The panel sky has no base of its own: the theme surface behind it shows through.
    if (plan.base.a > 0f) {
        drawRect(Brush.verticalGradient(*plan.baseStops.map { (y, a) -> y to base.copy(alpha = a) }.toTypedArray()))
    }
    scale(density, pivot = Offset.Zero) {
        for (s in plan.stars) {
            val color = s.color.toColor()
            val center = Offset(s.x, s.y)
            // A sampled Gaussian halo uses plain circles. Creating a radial GPU shader for
            // every star is unnecessary on the fallback path (and fragile on software GPUs).
            drawHalo(color, center, s.haloRadius, 0.3f * s.alpha)
            drawCircle(color.copy(alpha = s.alpha), radius = s.coreRadius, center = center)
        }
        for (c in plan.comets) {
            val color = c.color.toColor()
            val dx = c.headX - c.tailX
            val dy = c.headY - c.tailY
            val len = kotlin.math.hypot(dx, dy)
            val nx = -dy / len
            val ny = dx / len
            val streak = Path().apply {
                moveTo(c.headX + nx * c.headHalfWidth, c.headY + ny * c.headHalfWidth)
                lineTo(c.tailX + nx * c.tailHalfWidth, c.tailY + ny * c.tailHalfWidth)
                lineTo(c.tailX - nx * c.tailHalfWidth, c.tailY - ny * c.tailHalfWidth)
                lineTo(c.headX - nx * c.headHalfWidth, c.headY - ny * c.headHalfWidth)
                close()
            }
            drawPath(
                streak,
                Brush.linearGradient(
                    *SkyCanvasPlan.TAIL_STOPS.map { (f, a) -> f to color.copy(alpha = c.alpha * a) }.toTypedArray(),
                    start = Offset(c.headX, c.headY),
                    end = Offset(c.tailX, c.tailY),
                ),
            )
            val head = Offset(c.headX, c.headY)
            drawHalo(color, head, SkyCanvasPlan.GLOW_RADIUS, c.alpha)
        }
    }
}

private fun SkyRgba.toColor(): Color = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), a.coerceIn(0f, 1f))

private fun DrawScope.drawHalo(color: Color, center: Offset, radius: Float, alpha: Float) {
    var previous = 0f
    for (i in 6 downTo 1) {
        val profile = SkyCanvasPlan.HALO_STOPS[i].second
        val band = ((profile - previous) * alpha).coerceIn(0f, 1f)
        drawCircle(color.copy(alpha = band), radius * i / 6f, center)
        previous = profile
    }
}

/** The theme's colours as the palette both skies take. */
fun Ot3Colors.skyPalette(): SkyPalette = SkyPalette(
    background = background,
    foreground = foreground,
    accent = accent,
    lightSky = sky.lightSky,
    brightness = sky.brightness,
    nebula = sky.nebula,
    meteorRate = sky.meteorRate,
    ink = sky.ink,
)
