package dev.avillagran.ot3android.ui.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uniforms for a tokyo-night-like (dark) and a catppuccin-latte-like (light) palette. */
class SkyUniformsTest {
    private val tokyo = TokyoNight
    private val latte = CatppuccinLatte

    private fun uniforms(palette: SkyPalette, motion: SkyMotion = SkyMotion.Full, energy: Float = 1f) =
        SkyMath.uniforms(palette, motion, clock = 12f, drift = 300f, energy = energy, widthDp = 411f, heightDp = 126f, pixelDensity = 2.625f)

    private fun assertRgb(r: Int, g: Int, b: Int, c: SkyRgba) {
        assertEquals("r", r, Math.round(c.r * 255))
        assertEquals("g", g, Math.round(c.g * 255))
        assertEquals("b", b, Math.round(c.b * 255))
        assertEquals("a", 1f, c.a)
    }

    @Test
    fun paletteConstantsMatchTheThemeJson() {
        assertFalse(tokyo.lightSky)
        assertEquals(1.25f, tokyo.brightness)
        assertEquals(1f, tokyo.ink)
        assertTrue(latte.lightSky)
        assertEquals(0.9f, latte.brightness)
        assertEquals(1.7f, latte.ink)
        for (p in listOf(tokyo, latte)) {
            assertEquals(0.55f, p.nebula)
            assertEquals(0.85f, p.meteorRate)
        }
    }

    @Test
    fun tokyoNightDarkSky() {
        val u = uniforms(tokyo)
        // Qt.darker(#1a1b26, 2.4): every channel / 2.4.
        assertRgb(11, 11, 16, u.base)
        assertRgb(0xA9, 0xB1, 0xD6, u.star)
        assertRgb(255, 255, 255, u.meteor)
        assertRgb(0x7A, 0xA2, 0xF7, u.accent)
        assertEquals(1.25f, u.brightness)
        assertEquals(1f, u.ink)
        assertEquals(0.85f, u.meteors)
        assertEquals(0.55f, u.nebula)
        assertEquals(1f - 62f * 0.85f / 126f, u.fade, 1e-6f)
        assertEquals(411f, u.width)
        assertEquals(126f, u.height)
        assertEquals(2.625f, u.pixelDensity)
        assertEquals(12f, u.clock)
        assertEquals(300f, u.drift)
        assertEquals(0f, u.scroll)
        assertEquals(1f, u.opacity)
    }

    @Test
    fun catppuccinLatteLightSky() {
        val u = uniforms(latte)
        // Qt.tint(#eff1f5, #4c4f69 @ 0.06).
        assertRgb(229, 231, 237, u.base)
        assertRgb(0x4C, 0x4F, 0x69, u.star)
        assertRgb(0x4C, 0x4F, 0x69, u.meteor)
        assertEquals(0.9f, u.brightness)
        assertEquals(1.7f, u.ink)
    }

    @Test
    fun motionAndEnergyGateTheComets() {
        assertEquals(0f, uniforms(tokyo, energy = 0f).meteors)
        assertEquals(0.85f, uniforms(tokyo, motion = SkyMotion.Light).meteors)
        assertEquals(0f, uniforms(tokyo, motion = SkyMotion.Off).meteors)
    }

    @Test
    fun uniformsAreSetInDeclarationOrderWithTheirArity() {
        val seen = mutableListOf<Pair<String, Int>>()
        uniforms(tokyo).forEachUniform { name, values -> seen += name to values.size }
        assertEquals(
            listOf(
                "pixelDensity" to 1, "opacity" to 1, "drift" to 1, "clock" to 1, "meteors" to 1,
                "brightness" to 1, "nebula" to 1, "scroll" to 1, "fade" to 1, "size" to 2, "origin" to 2,
                "star" to 4, "accent" to 4, "base" to 4, "meteor" to 4, "ink" to 1,
            ),
            seen,
        )
    }

    @Test
    fun referenceShadeIsSolidBaseAtTheTopAndClearAtTheBottom() {
        val u = uniforms(tokyo, energy = 0f).copy(nebula = 0f, brightness = 0f)
        val top = SkyMath.shade(u, 200f, 1f)
        assertEquals(u.base.r, top[0], 1e-5f)
        assertEquals(1f, top[3], 1e-5f)
        val bottom = SkyMath.shade(u, 200f, 126f)
        assertEquals(0f, bottom[3], 1e-5f)
    }

    @Test
    fun nebulaLeansTowardsTheAccentTopLeft() {
        val u = uniforms(tokyo, energy = 0f).copy(brightness = 0f)
        val left = SkyMath.shade(u, 0f, 0f)
        val right = SkyMath.shade(u, 410f, 0f)
        // Accent is bluer than the base, so the left edge carries more blue.
        assertTrue(left[2] > right[2])
    }
}
