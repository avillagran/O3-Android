package dev.avillagran.ot3android.ui.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyMathTest {
    @Test
    fun hash12MatchesOt3sJavaScriptCopy() {
        // Values from T3Starfield.qml's hash12 run in JavaScript (double precision).
        val expected = mapOf(
            (0f to 0f) to 0.0,
            (1f to 1f) to 0.15251030963648304,
            (3f to 17f) to 0.30228420214734797,
            (12.5f to -4f) to 0.3129874284700236,
            (100f to 34f) to 0.387421943805748,
        )
        for ((p, want) in expected) {
            assertEquals("hash12$p", want, SkyMath.hash12(p.first, p.second).toDouble(), 0.01)
        }
    }

    @Test
    fun hashesAreDeterministicAndInRange() {
        for (x in -20..20) for (y in -20..20) {
            val h = SkyMath.hash12(x * 1.3f, y * 0.7f)
            assertEquals(h, SkyMath.hash12(x * 1.3f, y * 0.7f))
            assertTrue(h >= 0f && h < 1f)
            val (a, b) = SkyMath.hash22(x * 1.3f, y * 0.7f)
            assertEquals(a to b, SkyMath.hash22(x * 1.3f, y * 0.7f))
            assertTrue(a >= 0f && a < 1f && b >= 0f && b < 1f)
        }
    }

    @Test
    fun layersAreOt3s() {
        val l = SkyMath.LAYERS
        assertEquals(listOf(29f, 53f, 97f), l.map { it.cell })
        assertEquals(listOf(0.42f, 0.38f, 0.32f), l.map { it.density })
        assertEquals(listOf(1f, 19f, 43f), l.map { it.seed })
        assertEquals(listOf(0.55f, 0.8f, 1.15f), l.map { it.radius })
        assertEquals(listOf(0.55f, 0.8f, 1f), l.map { it.weight })
        assertEquals(listOf(0.30f, 0.62f, 1f), l.map { it.driftFactor })
        assertEquals(listOf(0.06f, 0.12f, 0.22f), l.map { it.scrollFactor })
        assertEquals(0.18f, SkyMath.FLOW_Y)
        assertEquals(5f, SkyMath.IDLE_SPEED)
        assertEquals(42f, SkyMath.BUSY_SPEED)
        assertEquals(1600L, SkyMath.ENERGY_RAMP_MS)
    }

    @Test
    fun starsStayInsideTheirCellWithinOt3sRanges() {
        for (layer in SkyMath.LAYERS) {
            var occupied = 0
            var total = 0
            for (ix in -60..60) for (iy in -40..40) {
                total++
                val star = SkyMath.star(layer, ix.toFloat(), iy.toFloat()) ?: continue
                occupied++
                val fx = star.centerX / layer.cell - ix
                val fy = star.centerY / layer.cell - iy
                assertTrue("x in cell $fx", fx in 0.199f..0.801f)
                assertTrue("y in cell $fy", fy in 0.199f..0.801f)
                assertTrue(star.radius in layer.radius * 0.55f - 1e-4f..layer.radius * 1.25f + 1e-4f)
                assertTrue(star.rate in 0.6f..2.8f)
                assertTrue(star.gain in 0.45f..1f)
                assertTrue(star.hash <= layer.density)
                assertEquals(SkyMath.cellTint(layer, ix.toFloat(), iy.toFloat()), star.tint)
            }
            // One star at most per cell, in about `density` of them.
            assertEquals(layer.density, occupied.toFloat() / total, 0.03f)
        }
    }

    @Test
    fun twinkleIsOt3sFormula() {
        assertEquals(0.55f + 0.45f * kotlin.math.sin(2f * 1.5f + 0.3f * 61f), SkyMath.twinkle(2f, 1.5f, 0.3f), 1e-6f)
        assertEquals(0.55f, SkyMath.twinkle(0f, 1f, 0f), 1e-6f)
        for (i in 0..200) {
            val v = SkyMath.twinkle(i * 0.37f, 0.6f + (i % 23) * 0.1f, (i % 17) / 17f)
            assertTrue(v in 0.0999f..1.0001f)
        }
    }

    @Test
    fun starProfileHasACoreAndAHalo() {
        val r = 1f
        assertEquals(1.3f, SkyMath.starProfile(0f, r), 1e-5f)
        assertTrue(SkyMath.starProfile(r + 0.9f, r) < 0.3f)
        assertEquals(0f, SkyMath.starProfile(20f, r), 1e-4f)
    }

    @Test
    fun cometChannelsUseOt3sPeriods() {
        assertEquals(listOf(2.1f, 3.47f, 4.84f, 6.21f), (0..3).map { SkyMath.cometPeriod(it) }.map { Math.round(it * 100) / 100f })
        assertEquals(4, SkyMath.COMET_CHANNELS)
    }

    @Test
    fun aChannelFiresOnlyWhenItsHashIsUnderTheLevel() {
        var fired = 0
        for (i in 0..2000) {
            val clock = i * 0.05f
            for (ch in 0..3) {
                val c = SkyMath.comet(ch, clock, 0.4f, 411f, 126f) ?: continue
                fired++
                assertTrue(SkyMath.hash12(c.slot, ch * 17f) <= 0.4f)
                assertTrue(c.k in 0f..1f)
                // Right to left and downwards.
                assertTrue(c.dirX < 0f && c.dirY > 0f)
            }
        }
        assertTrue(fired > 0)
    }

    @Test
    fun energyZeroMeansNoComets() {
        val level = SkyMath.meteorLevel(SkyMotion.Full, header = true, meteorRate = 0.85f, energy = 0f)
        assertEquals(0f, level)
        // hash12(0, 0) is exactly 0, so only the shader's > 0.001 guard keeps slot 0 dark.
        assertEquals(0f, SkyMath.hash12(0f, 0f))
        for (i in 0..2000) for (ch in 0..3) assertNull(SkyMath.comet(ch, i * 0.05f, level, 411f, 126f))
    }

    @Test
    fun levelOneFiresEveryRoll() {
        for (ch in 0..3) assertNotNull(SkyMath.comet(ch, SkyMath.cometPeriod(ch) * (5f - ch * 0.31f) + 0.3f, 1f, 411f, 126f))
    }

    @Test
    fun meteorLevelFollowsT3StarfieldLines47And48() {
        val rate = 0.85f
        assertEquals(0.85f, SkyMath.meteorLevel(SkyMotion.Full, true, rate, 1f))
        assertEquals(0.85f, SkyMath.meteorLevel(SkyMotion.Full, false, rate, 1f))
        assertEquals(0.85f, SkyMath.meteorLevel(SkyMotion.Light, true, rate, 1f))
        assertEquals(0f, SkyMath.meteorLevel(SkyMotion.Light, false, rate, 1f))
        assertEquals(0f, SkyMath.meteorLevel(SkyMotion.Off, true, rate, 1f))
        assertEquals(0.425f, SkyMath.meteorLevel(SkyMotion.Full, true, rate, 0.5f), 1e-6f)
        assertEquals(1f, SkyMath.meteorLevel(SkyMotion.Full, true, rate, 1f, meteorScale = 2.2f))
    }

    @Test
    fun tailIsClamped() {
        assertEquals(60f, SkyMath.tailLength(100f, 50f))
        assertEquals(kotlin.math.hypot(411f, 126f) * 0.22f, SkyMath.tailLength(411f, 126f), 1e-4f)
        assertEquals(240f, SkyMath.tailLength(2000f, 1000f))
    }

    @Test
    fun lightSkiesUseDarkInkWithMoreGain() {
        assertEquals(1.7f, SkyMath.inkGain(true))
        assertEquals(1f, SkyMath.inkGain(false))
        val fg = SkyRgba(0.3f, 0.31f, 0.41f)
        assertEquals(fg, SkyMath.meteorInk(fg, lightSky = true))
        assertEquals(SkyRgba.White, SkyMath.meteorInk(fg, lightSky = false))
        assertTrue(SkyMath.isLightSky(SkyRgba(0xEF / 255f, 0xF1 / 255f, 0xF5 / 255f)))
        assertFalse(SkyMath.isLightSky(SkyRgba(0x1A / 255f, 0x1B / 255f, 0x26 / 255f)))
    }

    @Test
    fun easingAndFadeAreOt3s() {
        assertEquals(0f, SkyMath.easeInOutSine(0f), 1e-6f)
        assertEquals(0.5f, SkyMath.easeInOutSine(0.5f), 1e-6f)
        assertEquals(1f, SkyMath.easeInOutSine(1f), 1e-6f)
        assertEquals(0.0954915f, SkyMath.easeInOutSine(0.2f), 1e-5f)
        assertEquals(1f - 62f * 0.85f / 126f, SkyMath.fade(126f, 62f), 1e-6f)
        assertEquals(0f, SkyMath.fade(0f, 62f))
        assertEquals(1f, SkyMath.edge(0.5f, 0.4f))
        assertEquals(0f, SkyMath.edge(0.5f, 1f))
        assertEquals(1f, SkyMath.edge(0f, 1f))
    }

    @Test
    fun smoothstepAcceptsReversedEdgesLikeTheShader() {
        assertEquals(1f, SkyMath.smoothstep(1f, 0.2f, 0f))
        assertEquals(0f, SkyMath.smoothstep(1f, 0.2f, 2f))
        assertEquals(0.5f, SkyMath.smoothstep(1f, 0f, 0.5f), 1e-6f)
    }

    @Test
    fun platformRulesOnlyLowerTheLevel() {
        assertEquals(SkyMotion.Off, SkyMotion.Full.limitedBy(animatorScale = 0f, powerSave = false))
        assertEquals(SkyMotion.Light, SkyMotion.Full.limitedBy(animatorScale = 1f, powerSave = true))
        assertEquals(SkyMotion.Off, SkyMotion.Off.limitedBy(animatorScale = 1f, powerSave = true))
        assertEquals(SkyMotion.Light, SkyMotion.Light.limitedBy(animatorScale = 0.5f, powerSave = false))
        assertEquals(SkyMotion.Full, SkyMotion.Full.limitedBy(animatorScale = 1f, powerSave = false))
    }
}
