package dev.avillagran.ot3android.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import dev.avillagran.ot3android.core.data.prefs.RevealPace
import dev.avillagran.ot3android.core.data.prefs.RevealStyle
import dev.avillagran.ot3android.core.data.prefs.AnimationPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ot3MotionTest {
    @Test
    fun `the preference is capped by the platform`() {
        val cases = listOf(
            // preference, animators off, power save -> level
            Triple(AnimationPreference.AUTO, false, false) to Ot3MotionLevel.FULL,
            Triple(AnimationPreference.FULL, false, false) to Ot3MotionLevel.FULL,
            Triple(AnimationPreference.LIGHT, false, false) to Ot3MotionLevel.LIGHT,
            Triple(AnimationPreference.OFF, false, false) to Ot3MotionLevel.OFF,
            Triple(AnimationPreference.AUTO, false, true) to Ot3MotionLevel.LIGHT,
            Triple(AnimationPreference.FULL, false, true) to Ot3MotionLevel.LIGHT,
            Triple(AnimationPreference.LIGHT, false, true) to Ot3MotionLevel.LIGHT,
            Triple(AnimationPreference.OFF, false, true) to Ot3MotionLevel.OFF,
            Triple(AnimationPreference.FULL, true, false) to Ot3MotionLevel.OFF,
            Triple(AnimationPreference.AUTO, true, true) to Ot3MotionLevel.OFF,
        )
        for ((input, expected) in cases) {
            assertEquals(input.toString(), expected, resolveMotionLevel(input.first, input.second, input.third))
        }
    }

    @Test
    fun `Off snaps every spec, Light and Full keep ot3's durations`() {
        assertTrue(Ot3MotionPolicy.Off.colorChange<Float>() is SnapSpec)
        assertTrue(Ot3MotionPolicy.Off.shelfMove<Float>() is SnapSpec)
        val light = Ot3MotionPolicy(Ot3MotionLevel.LIGHT)
        assertEquals(Ot3Motion.COLOR_CHANGE_MS, (light.colorChange<Float>() as TweenSpec).durationMillis)
        assertEquals(Ot3Motion.SHELF_MOVE_MS, (Ot3MotionPolicy.Full.shelfMove<Float>() as TweenSpec).durationMillis)
    }

    @Test fun `pace scales only both reveal paths and never overrides platform limits`() {
        val normal = Ot3MotionPolicy.Full
        val cases = listOf(Triple(RevealPace.SLOW, 832, 896), Triple(RevealPace.NORMAL, 520, 560), Triple(RevealPace.FAST, 312, 336))
        for ((pace, spring, nebula) in cases) {
            val paced = Ot3MotionPolicy(Ot3MotionLevel.FULL, revealPace = pace)
            assertEquals(spring, (paced.reveal<Float>() as TweenSpec).durationMillis)
            assertEquals(nebula, (paced.reveal<Float>(RevealStyle.NEBULA) as TweenSpec).durationMillis)
            assertEquals(normal.colorChange<Float>(), paced.colorChange<Float>())
            assertEquals(normal.fieldBorder<Float>(), paced.fieldBorder<Float>())
            assertEquals(normal.switchKnob<Float>(), paced.switchKnob<Float>())
            assertEquals(normal.rowArriveFade<Float>(), paced.rowArriveFade<Float>())
            assertEquals(normal.rowArriveDrift<Float>(), paced.rowArriveDrift<Float>())
            assertEquals(normal.rowDepart<Float>(), paced.rowDepart<Float>())
            assertEquals(normal.shelfMove<Float>(), paced.shelfMove<Float>())
            assertEquals(normal.statusPop<Float>(), paced.statusPop<Float>())
            assertEquals(normal.pane<Float>(), paced.pane<Float>())
            val saver = Ot3MotionPolicy(resolveMotionLevel(AnimationPreference.FULL, false, true), revealPace = pace)
            assertEquals(Ot3MotionLevel.LIGHT, saver.level)
            assertEquals(spring, (saver.reveal<Float>() as TweenSpec).durationMillis)
            for (off in listOf(Ot3MotionPolicy(Ot3MotionLevel.OFF, revealPace = pace),
                Ot3MotionPolicy(resolveMotionLevel(AnimationPreference.FULL, true, false), revealPace = pace))) {
                assertTrue(off.reveal<Float>() is SnapSpec)
                assertTrue(off.reveal<Float>(RevealStyle.NEBULA) is SnapSpec)
            }
        }
    }

    @Test fun `every style pace and capability obeys source timing and platform limits`() {
        for (style in RevealStyle.entries) for (pace in RevealPace.entries) {
            for (shaderAvailable in listOf(false, true)) for (level in Ot3MotionLevel.entries) {
                val motion = Ot3MotionPolicy(level, revealPace = pace, revealStyle = style)
                val effective = motion.effectiveRevealStyle(shaderAvailable)
                val expected = when {
                    level == Ot3MotionLevel.OFF || style == RevealStyle.INSTANT -> RevealStyle.INSTANT
                    !shaderAvailable -> RevealStyle.SPRING
                    else -> style
                }
                assertEquals("$style $pace $shaderAvailable $level", expected, effective)
                val spec = motion.reveal<Float>(effective)
                if (expected == RevealStyle.INSTANT) assertTrue(spec is SnapSpec)
                else {
                    spec as TweenSpec
                    val base = when (expected) { RevealStyle.NEBULA -> 560; RevealStyle.WARP -> 460; RevealStyle.FOLD -> 500; else -> 520 }
                    assertEquals(kotlin.math.round(base * pace.multiplier).toInt(), spec.durationMillis)
                    val t = 0.25f
                    val value = when (expected) {
                        RevealStyle.NEBULA -> 0.0625f
                        RevealStyle.WARP -> 0.03125f
                        RevealStyle.FOLD -> 0.1464466f
                        else -> 0.7609375f // Qt OutBack, overshoot 1.3
                    }
                    assertEquals(expected.name, value, spec.easing.transform(t), 0.00001f)
                }
                assertEquals(Ot3Motion.COLOR_CHANGE_MS, (Ot3MotionPolicy(Ot3MotionLevel.FULL, revealPace = pace, revealStyle = style).colorChange<Float>() as TweenSpec).durationMillis)
            }
        }
    }

    @Test
    fun `OutBack starts at 0, ends at 1 and overshoots`() {
        val easing = Ot3Motion.easeOutBack(2.4f)
        assertEquals(0f, easing.transform(0f), 1e-6f)
        assertEquals(1f, easing.transform(1f), 1e-6f)
        assertTrue(easing.transform(0.7f) > 1f)
    }
}
