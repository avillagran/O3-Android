package dev.avillagran.ot3android.ui.navigation

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/** T3Reveal's default nebula, not a navigation transition. Shader only while moving.
 * Natural 280 dp child never reflows; older APIs/software/compile failure use source spring.
 */
@Composable
internal fun SidebarReveal(shown: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
    val progress by animateFloatAsState(if (shown) 1f else 0f,
        motion.reveal(nebula), label = "sidebarReveal")
    val extent = progress.coerceIn(0f, 1f)
    val reach = if (nebula) (extent / 0.6f).coerceAtMost(1f) else extent
    val reverse = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.width(280.dp * reach).fillMaxHeight().clipToBounds()) {
        if (shown || progress > 0f) {
            Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(280.dp).fillMaxHeight().graphicsLayer {
                if (nebula && shader != null && Build.VERSION.SDK_INT >= 33 && extent > 0f && extent < 1f) {
                    shader.setFloatUniform("progress", progress)
                    shader.setFloatUniform("style", 0f)
                    shader.setFloatUniform("horizontal", 1f)
                    shader.setFloatUniform("reverse", if (reverse) 1f else 0f)
                    shader.setFloatUniform("seed", 0.371f)
                    shader.setFloatUniform("size", size.width / density, size.height / density)
                    shader.setFloatUniform("pixelDensity", density)
                    shader.setColorUniform("uGlow", android.graphics.Color.valueOf(colors.accent.red, colors.accent.green, colors.accent.blue))
                    renderEffect = effect
                } else if (!nebula) {
                    translationX = (if (reverse) 1 else -1) * (1f - extent) * size.width
                    alpha = (extent * 1.8f).coerceAtMost(1f)
                    transformOrigin = TransformOrigin(if (reverse) 1f else 0f, 0f)
                    scaleX = progress.coerceAtLeast(0.9f)
                }
            }) { content() }
        }
    }
}
