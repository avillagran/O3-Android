package dev.avillagran.ot3android.ui.sky

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

object SkyDefaults {
    /** ot3's brand row height, the part of the header the sky stays solid behind (`T3Panel.qml:1135`). */
    val BrandHeight: Dp = SkyMath.BRAND_HEIGHT.dp
}

/**
 * ot3's sidebar header sky (UI_SPEC 3.5): the procedural starfield behind [content],
 * solid behind the brand row and fading out below it. AGSL on API 33+, Canvas below.
 *
 * @param energy 0 idle … 1 something works; drift eases between 5 and 42 dp/s over
 *   1 600 ms and comets appear only above 0.
 * @param brandHeight height of the brand row; the sky fades over the rest
 *   (`fade = 1 − brandHeight × 0.85 / height`).
 * @param onAutoDegrade non-null when [motion] is the Auto preference: the header watches
 *   its ticks and, after 90 slow ones while working, drops to [SkyMotion.Light] for the
 *   rest of its life and calls this once so the preference can remember it.
 */
@Composable
fun SkyHeader(
    palette: SkyPalette,
    motion: SkyMotion,
    energy: Float,
    modifier: Modifier = Modifier,
    brandHeight: Dp = SkyDefaults.BrandHeight,
    onAutoDegrade: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val system by rememberSystemMotionLimits()
    var degraded by remember { mutableStateOf(false) }
    val requested = if (degraded && motion == SkyMotion.Full) SkyMotion.Light else motion
    val effective = requested.limitedBy(system.animatorScale, system.powerSave)
    val auto = onAutoDegrade != null && !degraded
    val animated = effective != SkyMotion.Off

    val sky = remember { SkyClock() }
    var tick by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    SideEffect {
        sky.meteorReach = palette.meteorRate
        sky.setEnergyTarget(if (animated) energy else 0f)
    }

    val currentMotion by rememberUpdatedState(effective)
    val currentAuto by rememberUpdatedState(auto)
    val currentOnDegrade by rememberUpdatedState(onAutoDegrade)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (animated && visible) {
        LaunchedEffect(sky, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                sky.resetTiming()
                while (true) {
                    val now = withFrameNanos { it }
                    val wasDegraded = sky.degraded
                    val frame = sky.onFrame(now, currentMotion, currentAuto)
                    if (frame.ticked) tick++
                    if (!wasDegraded && sky.degraded) {
                        degraded = true
                        currentOnDegrade?.invoke()
                    }
                    if (frame.waitMs > 0) delay(frame.waitMs)
                }
            }
        }
    }

    // Typed as Shader so nothing below API 33 touches RuntimeShader.
    val shader: Shader? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) remember { StarfieldShaders.create() } else null
    Box(
        modifier
            .onGloballyPositioned { visible = it.boundsInWindow().let { b -> b.width > 0f && b.height > 0f } }
            .drawWithCache {
                val brush = shader?.let { ShaderBrush(it) }
                onDrawBehind {
                    // Reading tick redraws on every tick.
                    if (tick < 0 || size.width <= 0f || size.height <= 0f) return@onDrawBehind
                    val u = SkyMath.uniforms(
                        palette = palette,
                        motion = effective,
                        clock = sky.clock,
                        drift = sky.drift,
                        energy = sky.energy,
                        widthDp = size.width / density,
                        heightDp = size.height / density,
                        pixelDensity = density,
                        brandHeightDp = brandHeight.value,
                    )
                    if (shader != null && brush != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        StarfieldShaders.apply(shader, u)
                        drawRect(brush)
                    } else {
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
    drawRect(Brush.verticalGradient(*plan.baseStops.map { (y, a) -> y to base.copy(alpha = a) }.toTypedArray()))
    scale(density, pivot = Offset.Zero) {
        for (s in plan.stars) {
            val color = s.color.toColor()
            val center = Offset(s.x, s.y)
            drawCircle(
                Brush.radialGradient(
                    *SkyCanvasPlan.HALO_STOPS.map { (f, a) -> f to color.copy(alpha = 0.3f * s.alpha * a) }.toTypedArray(),
                    center = center,
                    radius = s.haloRadius,
                ),
                radius = s.haloRadius,
                center = center,
            )
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
            drawCircle(
                Brush.radialGradient(
                    *SkyCanvasPlan.HALO_STOPS.map { (f, a) -> f to color.copy(alpha = c.alpha * a) }.toTypedArray(),
                    center = head,
                    radius = SkyCanvasPlan.GLOW_RADIUS,
                ),
                radius = SkyCanvasPlan.GLOW_RADIUS,
                center = head,
            )
        }
    }
}

private fun SkyRgba.toColor(): Color = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), a.coerceIn(0f, 1f))
