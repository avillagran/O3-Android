package dev.avillagran.ot3android.ui.navigation

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import dev.avillagran.ot3android.ui.theme.Ot3Motion
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlin.math.roundToInt

/** T3Reveal.qml's vertical axis: natural child size, nebula, source spring fallback. */
@Composable
internal fun VerticalReveal(shown: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = Ot3Theme.motion
    val colors = Ot3Theme.colors
    val hardware = LocalView.current.isHardwareAccelerated
    val shader = if (Build.VERSION.SDK_INT >= 33 && hardware) remember {
        runCatching { RuntimeShader(REVEAL_AGSL) }.getOrNull()
    } else null
    val effect = if (Build.VERSION.SDK_INT >= 33 && shader != null) remember(shader) {
        runCatching { RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect() }.getOrNull()
    } else null
    val nebula = effect != null
    val seed = remember { kotlin.random.Random.nextFloat() }
    val progress = if (!motion.animates) {
        if (shown) 1f else 0f
    } else {
        val animated by animateFloatAsState(if (shown) 1f else 0f,
            if (nebula) motion.tween(560, Ot3Motion.EaseInOutCubic) else motion.reveal(), label = "verticalReveal")
        animated
    }
    val extent = progress.coerceIn(0f, 1f)
    val reach = if (nebula) (extent / 0.6f).coerceAtMost(1f) else extent
    SubcomposeLayout(modifier.clipToBounds()) { constraints ->
        val child = subcompose("content") {
            Box { if (shown || progress > 0f) content() }
        }.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        layout(constraints.constrainWidth(child.width), constraints.constrainHeight((child.height * reach).roundToInt())) {
            child.placeWithLayer(0, if (nebula) 0 else (-child.height * (1f - extent)).roundToInt()) {
                if (nebula && shader != null && Build.VERSION.SDK_INT >= 33 && extent > 0f && extent < 1f) {
                    shader.setFloatUniform("progress", progress)
                    shader.setFloatUniform("style", 0f)
                    shader.setFloatUniform("horizontal", 0f)
                    shader.setFloatUniform("reverse", 0f)
                    shader.setFloatUniform("seed", seed)
                    shader.setFloatUniform("size", size.width / density, size.height / density)
                    shader.setFloatUniform("pixelDensity", density)
                    shader.setColorUniform("uGlow", android.graphics.Color.valueOf(colors.accent.red, colors.accent.green, colors.accent.blue))
                    renderEffect = effect
                } else if (!nebula) {
                    alpha = (extent * 1.8f).coerceAtMost(1f)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleY = progress.coerceAtLeast(0.9f)
                }
            }
        }
    }
}
