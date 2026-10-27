package dev.avillagran.ot3android.ui.navigation

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalView
import dev.avillagran.ot3android.core.data.prefs.RevealStyle
import dev.avillagran.ot3android.ui.theme.Ot3Theme

internal val RevealStyle.shaded: Boolean get() = this == RevealStyle.NEBULA || this == RevealStyle.WARP || this == RevealStyle.FOLD
internal val RevealStyle.shaderIndex: Float get() = when (this) {
    RevealStyle.NEBULA -> 0f
    RevealStyle.WARP -> 1f
    RevealStyle.FOLD -> 2f
    else -> error("Unshaded reveal has no shader index")
}
internal fun revealReach(style: RevealStyle, progress: Float): Float {
    val extent = progress.coerceIn(0f, 1f)
    return if (style == RevealStyle.NEBULA) (extent / 0.6f).coerceAtMost(1f) else extent
}

internal data class RevealState(val style: RevealStyle, val progress: Float, val shader: RuntimeShader?, val effect: ComposeRenderEffect?, val seed: Float) {
    val extent get() = progress.coerceIn(0f, 1f)
    val reach get() = revealReach(style, progress)
}

/** Both axes use T3Reveal.qml's selection, fallback and per-instance noise seed. */
@Composable
internal fun rememberRevealState(shown: Boolean, label: String): RevealState {
    val motion = Ot3Theme.motion
    val hardware = LocalView.current.isHardwareAccelerated
    val shader = if (motion.animates && motion.revealStyle.shaded && Build.VERSION.SDK_INT >= 33 && hardware) remember {
        runCatching { RuntimeShader(REVEAL_AGSL) }.getOrNull()
    } else null
    // Capability probe only; the attached effect must be created after each frame's uniforms.
    val effect = if (Build.VERSION.SDK_INT >= 33 && shader != null) remember(shader) {
        runCatching { RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect() }.getOrNull()
    } else null
    val style = motion.effectiveRevealStyle(effect != null)
    // Removing the animation branch is intentional: changing only an animationSpec
    // with an unchanged target does not cancel animateFloatAsState's in-flight job.
    val progress = if (style == RevealStyle.INSTANT) {
        if (shown) 1f else 0f
    } else {
        val animated by animateFloatAsState(if (shown) 1f else 0f, motion.reveal(style), label = label)
        animated
    }
    val seed = remember { kotlin.random.Random.nextFloat() }
    return RevealState(style, progress, shader, effect, seed)
}
