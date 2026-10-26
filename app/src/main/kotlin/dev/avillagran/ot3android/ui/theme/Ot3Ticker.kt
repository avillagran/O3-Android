package dev.avillagran.ot3android.ui.theme

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** One phase/loop for sky, spinner, shimmer and rows: T3Ticker.qml. Fastest visible request wins. */
internal data class Ot3TickFrame(val seconds: Float = 0f, val nanos: Long = 0L)
internal class Ot3Ticker {
    val requests = mutableStateMapOf<Any, Int>()
    val frame = mutableStateOf(Ot3TickFrame())
}
internal val LocalOt3Ticker = staticCompositionLocalOf<Ot3Ticker?> { null }

@Composable
internal fun ProvideOt3Ticker(motion: Ot3MotionPolicy, content: @Composable () -> Unit) {
    val inherited = LocalOt3Ticker.current
    val ticker = inherited ?: remember { Ot3Ticker() }
    if (inherited == null) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val wanted = ticker.requests.values.minOrNull() ?: 0
        val interval = if (motion.level == Ot3MotionLevel.LIGHT && wanted > 0) maxOf(33, wanted) else wanted
        LaunchedEffect(ticker, lifecycle, motion.animates, interval) {
            if (!motion.animates || interval == 0) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var previous = 0L
                while (true) {
                    val now = withInfiniteAnimationFrameNanos { it }
                    val dt = if (previous == 0L) interval / 1000f else ((now - previous) / 1e9f).coerceAtMost(0.25f)
                    previous = now
                    ticker.frame.value = Ot3TickFrame(ticker.frame.value.seconds + dt, now)
                    if (interval > 16) delay((interval - 6).toLong())
                }
            }
        }
    }
    CompositionLocalProvider(LocalOt3Ticker provides ticker, content = content)
}

@Composable
internal fun rememberOt3Tick(running: Boolean, intervalMs: Int = 16): State<Ot3TickFrame> {
    val ticker = checkNotNull(LocalOt3Ticker.current) { "Wrap decorative animation in Ot3Theme" }
    val ticket = remember { Any() }
    val active = running && LocalOt3Motion.current.animates
    DisposableEffect(ticker, active, intervalMs) {
        if (active) ticker.requests[ticket] = intervalMs.coerceAtLeast(8)
        onDispose { ticker.requests.remove(ticket) }
    }
    return ticker.frame
}
