package dev.avillagran.ot3android.ui.navigation

import android.os.Build
import android.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.core.data.prefs.RevealStyle
import kotlin.math.roundToInt

/** T3Reveal.qml's vertical axis: natural child size and the selected show/hide style. */
@Composable
internal fun VerticalReveal(shown: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = Ot3Theme.colors
    val state = rememberRevealState(shown, "verticalReveal")
    val (style, progress, shader, _, seed) = state
    val extent = state.extent
    val reach = state.reach
    val moving = progress != if (shown) 1f else 0f
    val clip = if (reach < 1f || moving) Modifier.clipToBounds() else Modifier
    SubcomposeLayout(modifier.then(clip)) { constraints ->
        val child = subcompose("content") {
            Box { if (shown || progress > 0f) content() }
        }.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        layout(constraints.constrainWidth(child.width), constraints.constrainHeight((child.height * reach).roundToInt())) {
            child.placeWithLayer(0, if (style == RevealStyle.SPRING) (-child.height * (1f - extent)).roundToInt() else 0) {
                if (style.shaded && shader != null && Build.VERSION.SDK_INT >= 33 && moving) {
                    shader.setFloatUniform("progress", progress)
                    shader.setFloatUniform("style", style.shaderIndex)
                    shader.setFloatUniform("horizontal", 0f)
                    shader.setFloatUniform("reverse", 0f)
                    shader.setFloatUniform("seed", seed)
                    shader.setFloatUniform("size", size.width / density, size.height / density)
                    shader.setFloatUniform("pixelDensity", density)
                    shader.setColorUniform("uGlow", android.graphics.Color.valueOf(colors.accent.red, colors.accent.green, colors.accent.blue))
                    // RenderEffect snapshots Skia's builder/uniforms. A remembered
                    // effect would keep its initial zero uniforms for the whole reveal.
                    renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
                } else if (style == RevealStyle.SPRING) {
                    alpha = (extent * 1.8f).coerceAtMost(1f)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleY = progress.coerceAtLeast(0.9f)
                }
            }
        }
    }
}
