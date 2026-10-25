package dev.avillagran.ot3android.ui.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyClockTest {
    private val frameNs = 16_666_667L

    /** Runs a 60 Hz display for [seconds], honouring the clock's sleep requests. Returns tick count. */
    private fun SkyClock.run(seconds: Float, motion: SkyMotion, auto: Boolean, start: Long = 0L, frame: Long = frameNs): Pair<Int, Long> {
        var now = start
        var ticks = 0
        val end = start + (seconds * 1e9f).toLong()
        while (now < end) {
            val f = onFrame(now, motion, auto)
            if (f.ticked) ticks++
            // Sleep, then wait for the next vsync.
            val wake = now + f.waitMs * 1_000_000L
            now += frame
            while (now < wake) now += frame
        }
        return ticks to now
    }

    @Test
    fun idleAutoTicksAtTheSlowPaceAndDriftsFiveDpPerSecond() {
        val sky = SkyClock().apply { setEnergyTarget(0f) }
        val (ticks, _) = sky.run(2f, SkyMotion.Full, auto = true)
        assertEquals(2f / 0.125f, ticks.toFloat(), 2f)
        assertEquals(5f * 2f, sky.drift, 0.8f)
        assertEquals(2f, sky.clock, 0.15f)
    }

    @Test
    fun explicitFullTicksAtThirtyHertzEvenIdle() {
        val sky = SkyClock().apply { setEnergyTarget(0f); meteorReach = 0f }
        assertEquals(33L, sky.intervalMs(SkyMotion.Full, auto = false))
        val (ticks, _) = sky.run(1f, SkyMotion.Full, auto = false)
        assertEquals(30f, ticks.toFloat(), 2f)
    }

    @Test
    fun lightNeverTicksFasterThanFortyMs() {
        val sky = SkyClock().apply { setEnergyTarget(1f); meteorReach = 0f }
        sky.onFrame(0L, SkyMotion.Light, auto = false)
        assertEquals(40L, sky.intervalMs(SkyMotion.Light, auto = false))
        sky.setEnergyTarget(0f)
        sky.onFrame(frameNs, SkyMotion.Light, auto = false)
        assertEquals(125L, sky.intervalMs(SkyMotion.Light, auto = false))
    }

    @Test
    fun workingDriftsAtFortyTwoDpPerSecond() {
        val sky = SkyClock().apply { setEnergyTarget(1f) }
        // The first target applies at once, like a QML Behavior's initial value.
        sky.onFrame(0L, SkyMotion.Full, auto = true)
        assertEquals(1f, sky.energy)
        val before = sky.drift
        sky.run(1f, SkyMotion.Full, auto = true, start = frameNs)
        assertEquals(42f, sky.drift - before, 2.5f)
    }

    @Test
    fun energyEasesOverSixteenHundredMsInOutSine() {
        val sky = SkyClock().apply { setEnergyTarget(0f) }
        sky.onFrame(0L, SkyMotion.Full, auto = false)
        sky.setEnergyTarget(1f)
        sky.onFrame(1_000_000L, SkyMotion.Full, auto = false)
        assertTrue(sky.busy)
        assertEquals(0.5f, sky.energyAt(1_000_000L + 800_000_000L), 1e-3f)
        assertEquals(SkyMath.easeInOutSine(0.25f), sky.energyAt(1_000_000L + 400_000_000L), 1e-3f)
        assertEquals(1f, sky.energyAt(1_000_000L + 1_600_000_000L), 0f)
        // Back to idle from wherever it is.
        sky.setEnergyTarget(0f)
        sky.onFrame(1_000_000L + 800_000_000L, SkyMotion.Full, auto = false)
        assertEquals(0.5f, sky.energyAt(1_000_000L + 800_000_000L), 1e-3f)
        assertEquals(0f, sky.energyAt(1_000_000L + 2_400_000_000L), 0f)
    }

    @Test
    fun aStepIsCappedAtAQuarterSecond() {
        val sky = SkyClock().apply { setEnergyTarget(0f) }
        sky.onFrame(0L, SkyMotion.Full, auto = true)
        val c = sky.clock
        sky.onFrame(5_000_000_000L, SkyMotion.Full, auto = true)
        assertEquals(0.25f, sky.clock - c, 1e-5f)
    }

    @Test
    fun autoDegradesAfterNinetySlowTicksWhileWorking() {
        val sky = SkyClock().apply { setEnergyTarget(1f); meteorReach = 0f }
        var now = 0L
        var ticks = 0
        // Every tick lands 100 ms apart: more than twice the 33 ms budget.
        while (!sky.degraded && ticks < 200) {
            if (sky.onFrame(now, SkyMotion.Full, auto = true).ticked) ticks++
            now += 100_000_000L
        }
        assertTrue(sky.degraded)
        // The first tick has no dt to judge; then 91 slow ones.
        assertEquals(92, ticks)
    }

    @Test
    fun autoStaysFullWhenTicksKeepUpOrNothingWorks() {
        val keepsUp = SkyClock().apply { setEnergyTarget(1f); meteorReach = 0f }
        keepsUp.run(10f, SkyMotion.Full, auto = true)
        assertFalse(keepsUp.degraded)
        assertEquals(0f, keepsUp.slowTicks)

        val idle = SkyClock().apply { setEnergyTarget(0f) }
        var now = 0L
        repeat(300) { idle.onFrame(now, SkyMotion.Full, auto = true); now += 400_000_000L }
        assertFalse(idle.degraded)

        val explicit = SkyClock().apply { setEnergyTarget(1f); meteorReach = 0f }
        now = 0L
        repeat(300) { explicit.onFrame(now, SkyMotion.Full, auto = false); now += 100_000_000L }
        assertFalse(explicit.degraded)
    }

    @Test
    fun aCometOnScreenKeepsTheAmbientPace() {
        val sky = SkyClock().apply { setEnergyTarget(1f) }
        var sawMeteor = false
        var now = 0L
        repeat(600) {
            sky.onFrame(now, SkyMotion.Light, auto = false)
            if (sky.meteorNow) {
                sawMeteor = true
                assertEquals(33L, sky.intervalMs(SkyMotion.Light, auto = false))
            }
            now += 33_000_000L
        }
        assertTrue(sawMeteor)
    }

    @Test
    fun restartingForgetsTheLastTick() {
        val sky = SkyClock().apply { setEnergyTarget(0f) }
        sky.onFrame(0L, SkyMotion.Full, auto = true)
        sky.resetTiming()
        val c = sky.clock
        // After a pause the first step is one interval, not the time spent paused.
        assertTrue(sky.onFrame(60_000_000_000L, SkyMotion.Full, auto = true).ticked)
        assertEquals(0.125f, sky.clock - c, 1e-5f)
    }
}
