package dev.avillagran.ot3android.ui.kit

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlin.math.sin

/** The real row's comet / attention glints / state-change star (T3ThreadRow.qml:183-211).
 * A static attention outline survives reduced motion; working never produces an idle aura.
 */
@Composable
internal fun ThreadAura(row: ThreadRowModel, slim: Boolean, modifier: Modifier = Modifier) {
    val colors = Ot3Theme.colors
    val motion = Ot3Theme.motion
    val role = row.status?.role
    val attention = role in listOf(StatusRole.INPUT, StatusRole.WAITING) || row.status?.iconName == "shield-question"
    val working = !attention && role == StatusRole.WORKING && motion.animates
    val workAmount by animateFloatAsState(if (working) 1f else 0f, motion.tween(600), label = "rowWorkingAura")
    val attentionAmount by animateFloatAsState(if (attention) 1f else 0f, motion.tween(600), label = "rowAttentionAura")
    val burst = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(row.status) }
    LaunchedEffect(row.status, motion.animates) {
        if (previous != row.status && motion.animates) {
            burst.snapTo(0.001f)
            burst.animateTo(1f, tween(1300))
        } else burst.snapTo(1f)
        previous = row.status
    }
    if (slim || (attentionAmount <= 0f && workAmount <= 0f && burst.value >= 1f)) return
    val time by rememberKitClock(workAmount > 0f || attentionAmount > 0f)
    val shader = if (Build.VERSION.SDK_INT >= 33) remember {
        runCatching { RuntimeShader(THREAD_AURA_AGSL) }.getOrNull()
    } else null
    Canvas(modifier) {
        if (Build.VERSION.SDK_INT >= 33 && shader != null) {
            shader.setFloatUniform("clock", time)
            shader.setFloatUniform("working", workAmount)
            shader.setFloatUniform("attention", attentionAmount)
            shader.setFloatUniform("burst", burst.value)
            shader.setFloatUniform("radius", 6f)
            shader.setFloatUniform("seed", (row.title.hashCode().toLong() and 0xffff).toFloat() / 65535f)
            shader.setFloatUniform("intensity", 1f)
            shader.setFloatUniform("workingStyle", 0f)
            shader.setFloatUniform("attentionStyle", if (motion.animates) 0f else 2f)
            shader.setFloatUniform("burstStyle", 0f)
            shader.setFloatUniform("size", size.width / density, size.height / density)
            shader.setFloatUniform("pixelDensity", density)
            shader.setColorUniform("tone", android.graphics.Color.valueOf(colors.working.red, colors.working.green, colors.working.blue))
            shader.setColorUniform("alert", android.graphics.Color.valueOf(colors.input.red, colors.input.green, colors.input.blue))
            shader.setColorUniform("flash", android.graphics.Color.valueOf(colors.accent.red, colors.accent.green, colors.accent.blue))
            drawRect(ShaderBrush(shader))
        } else {
            // Safe Canvas fallback: ot3's pulse style, same clock/rate as threadaura.frag.
            val pulse = if (!motion.animates) 0.6f else 0.5f + 0.5f * sin(time * if (attention) 2.6f else 1.8f)
            drawRoundRect((if (attention) colors.input else colors.working).copy(alpha = (0.35f + 0.5f * pulse) * maxOf(workAmount, attentionAmount)),
                cornerRadius = CornerRadius(6 * density), style = Stroke(density))
        }
    }
}
