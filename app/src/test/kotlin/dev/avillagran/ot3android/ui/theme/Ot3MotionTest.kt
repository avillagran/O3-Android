package dev.avillagran.ot3android.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
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

    @Test
    fun `OutBack starts at 0, ends at 1 and overshoots`() {
        val easing = Ot3Motion.easeOutBack(2.4f)
        assertEquals(0f, easing.transform(0f), 1e-6f)
        assertEquals(1f, easing.transform(1f), 1e-6f)
        assertTrue(easing.transform(0.7f) > 1f)
    }
}
