package dev.avillagran.ot3android.ui.sky

import kotlin.math.max
import kotlin.math.min

/**
 * The sky's clock, ot3's `T3Starfield` leader (`ot3:ui/qml/T3Starfield.qml:66-117`) on
 * ot3's "saver" smoothness (`ot3:ui/qml/T3Ticker.qml:22-27`): an idle Auto or Light sky
 * ticks every 125 ms (drift of 5 dp/s looks the same), everything else every 33 ms, and
 * Light never faster than 40 ms unless a comet is on screen. Pure Kotlin: the composable
 * feeds it frame times and reads [clock], [drift] and [energy].
 */
internal class SkyClock {
    var clock = 0f
        private set
    var drift = 0f
        private set

    /** Eased energy at the last tick: 0 idle, 1 working. */
    var energy = 0f
        private set

    /** Auto gave up on Full for good (`T3Starfield.qml:112-116`). */
    var degraded = false
        private set
    var slowTicks = 0f
        private set
    var meteorNow = false
        private set

    /** Highest comet chance of the header, its palette's `meteorRate` (`T3Panel.qml:852`). */
    var meteorReach = 0.85f

    private var target: Float? = null
    private var hasEnergy = false
    private var rampFrom = 0f
    private var rampTo = 0f
    private var rampStart = -1L
    private var lastTick = -1L

    /** Busy as ot3 means it: something works, the energy target is above 0. */
    val busy: Boolean get() = rampTo > 0f

    /**
     * Sets where the energy eases to over 1 600 ms InOutSine (`T3Starfield.qml:27-28`),
     * starting at the next frame. The first target applies at once, as a QML `Behavior`
     * does not animate a property's initial value.
     */
    fun setEnergyTarget(value: Float) {
        target = value.coerceIn(0f, 1f)
    }

    /** The loop stopped or restarted (`T3Starfield.qml:100`). */
    fun resetTiming() {
        lastTick = -1L
        slowTicks = 0f
    }

    /** Milliseconds between ticks for [motion] (`T3Starfield.qml:98-99`, saver pace). */
    fun intervalMs(motion: SkyMotion, auto: Boolean): Long {
        val slow = !meteorNow && !(motion == SkyMotion.Full && !auto) && !busy
        val base = if (slow) SLOW_MS else AMBIENT_MS
        return if (motion == SkyMotion.Light && !meteorNow) max(LIGHT_MIN_MS, base) else base
    }

    fun energyAt(nowNanos: Long): Float {
        if (rampStart < 0L) return rampTo
        val t = (nowNanos - rampStart) / 1_000_000f / SkyMath.ENERGY_RAMP_MS
        return if (t >= 1f) rampTo else SkyMath.mix(rampFrom, rampTo, SkyMath.easeInOutSine(t))
    }

    /** Result of one display frame: whether the sky moved and how long to sleep before the next. */
    data class Frame(val ticked: Boolean, val waitMs: Long)

    /**
     * Called on every display frame the loop wakes for. Ticks when an interval has
     * passed (within [TOLERANCE_MS], so a 33 ms interval lands on every second 60 Hz
     * frame) and asks to sleep until shortly before the next one.
     */
    fun onFrame(nowNanos: Long, motion: SkyMotion, auto: Boolean): Frame {
        applyTarget(nowNanos)
        val interval = intervalMs(motion, auto)
        val elapsedMs = if (lastTick < 0L) Long.MAX_VALUE else (nowNanos - lastTick) / 1_000_000L
        if (elapsedMs < interval - TOLERANCE_MS) {
            return Frame(ticked = false, waitMs = max(0L, interval - elapsedMs - WAKE_EARLY_MS))
        }
        tick(nowNanos, interval, motion, auto)
        return Frame(ticked = true, waitMs = max(0L, intervalMs(motion, auto) - WAKE_EARLY_MS))
    }

    private fun applyTarget(nowNanos: Long) {
        val next = target ?: return
        target = null
        if (!hasEnergy) {
            hasEnergy = true
            rampFrom = next; rampTo = next; energy = next
            return
        }
        if (next == rampTo) return
        rampFrom = energyAt(nowNanos)
        rampTo = next
        rampStart = nowNanos
    }

    private fun tick(nowNanos: Long, interval: Long, motion: SkyMotion, auto: Boolean) {
        val dt = if (lastTick < 0L) interval / 1000f else min((nowNanos - lastTick) / 1e9f, MAX_STEP_S)
        lastTick = nowNanos
        if (dt <= 0f) return
        clock += dt
        energy = energyAt(nowNanos)
        drift += dt * (SkyMath.IDLE_SPEED + (SkyMath.BUSY_SPEED - SkyMath.IDLE_SPEED) * energy)
        // Look one slow tick ahead so a comet never starts at the low rate.
        val reach = (if (motion == SkyMotion.Off) 0f else min(1f, meteorReach)) * energy
        meteorNow = reach > 0.001f && SkyMath.meteorLive(clock + 0.13f, reach)
        // Ticks sustained at twice their interval: this device can't keep up.
        if (auto && motion == SkyMotion.Full && busy && !meteorNow) {
            slowTicks = if (dt > interval / 500f) slowTicks + 1f else max(0f, slowTicks - 0.5f)
            if (slowTicks > SLOW_TICK_LIMIT) degraded = true
        }
    }

    companion object {
        const val SLOW_MS = 125L
        const val AMBIENT_MS = 33L
        const val LIGHT_MIN_MS = 40L
        const val TOLERANCE_MS = 4L
        const val WAKE_EARLY_MS = 12L
        const val SLOW_TICK_LIMIT = 90f

        /** ot3's per-frame time cap (`T3Ticker.qml:52`). */
        const val MAX_STEP_S = 0.25f
    }
}
