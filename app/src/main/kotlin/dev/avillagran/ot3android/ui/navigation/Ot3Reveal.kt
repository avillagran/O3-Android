package dev.avillagran.ot3android.ui.navigation

import android.os.Build
import android.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.core.data.prefs.RevealStyle

/** T3Reveal's selected show/hide style; the natural 280 dp child never reflows. */
@Composable
internal fun SidebarReveal(shown: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = Ot3Theme.colors
    val state = rememberRevealState(shown, "sidebarReveal")
    val (style, progress, shader, _, seed) = state
    val extent = state.extent
    val reach = state.reach
    val moving = progress != if (shown) 1f else 0f
    val clip = if (reach < 1f || moving) Modifier.clipToBounds() else Modifier
    val reverse = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.width(280.dp * reach).fillMaxHeight().then(clip)) {
        if (shown || progress > 0f) {
            Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(280.dp).fillMaxHeight().graphicsLayer {
                if (style.shaded && shader != null && Build.VERSION.SDK_INT >= 33 && moving) {
                    shader.setFloatUniform("progress", progress)
                    shader.setFloatUniform("style", style.shaderIndex)
                    shader.setFloatUniform("horizontal", 1f)
                    shader.setFloatUniform("reverse", if (reverse) 1f else 0f)
                    shader.setFloatUniform("seed", seed)
                    shader.setFloatUniform("size", size.width / density, size.height / density)
                    shader.setFloatUniform("pixelDensity", density)
                    shader.setColorUniform("uGlow", android.graphics.Color.valueOf(colors.accent.red, colors.accent.green, colors.accent.blue))
                    // RenderEffect snapshots Skia's builder/uniforms. A remembered
                    // effect would keep its initial zero uniforms for the whole reveal.
                    renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
                } else if (style == RevealStyle.SPRING) {
                    translationX = (if (reverse) 1 else -1) * (1f - extent) * size.width
                    alpha = (extent * 1.8f).coerceAtMost(1f)
                    transformOrigin = TransformOrigin(if (reverse) 1f else 0f, 0f)
                    scaleX = progress.coerceAtLeast(0.9f)
                }
            }) { content() }
        }
    }
}
