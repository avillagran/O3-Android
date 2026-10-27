package dev.avillagran.ot3android.ui.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ot3's panel sky (`ot3:ui/qml/T3Panel.qml:842-856`): the field behind the chat and the
 * whole sidebar. These checks are about the drawn region, not about the widgets in front of
 * it: a screen can contain the right text and buttons and still have no stars behind them.
 */
class SkyPanelTest {
    private val width = 411f
    private val height = 891f

    private fun panel(
        palette: SkyPalette,
        motion: SkyMotion = SkyMotion.Full,
        energy: Float = 1f,
        clock: Float = 12f,
        opaque: Boolean = false,
    ): SkyUniforms {
        val u = SkyMath.uniforms(
            palette.panel(), motion, clock = clock, drift = 300f, energy = energy,
            widthDp = width, heightDp = height, pixelDensity = 2.625f,
            brandHeightDp = 0f, header = false, fade = 0f,
        )
        return u.copy(base = SkyMath.panelBase(palette.background.toSkyRgba(), opaque))
    }

    @Test
    fun panelKeepsTheReferenceConstants() {
        val tokyo = panel(TokyoNight)
        assertEquals("brightness", SkyMath.PANEL_BRIGHTNESS, tokyo.brightness, 0f)
        assertEquals("nebula", SkyMath.PANEL_NEBULA, tokyo.nebula, 0f)
        assertEquals("meteor rate", SkyMath.PANEL_METEOR_RATE, tokyo.meteors, 1e-6f)
        assertEquals("no fade", 0f, tokyo.fade, 0f)
        // Light keeps comets to the header only; the panel never shows them
        // (`T3Starfield.qml:47-48`).
        assertEquals(0f, panel(TokyoNight, motion = SkyMotion.Light).meteors, 0f)
        assertEquals(0f, panel(TokyoNight, energy = 0f).meteors, 0f)
        assertEquals(0f, panel(TokyoNight, motion = SkyMotion.Off).meteors, 0f)
    }

    @Test
    fun panelIsTransparentOverTheSurfaceAndOpaqueWhereItCoversTheChat() {
        val transparent = panel(TokyoNight)
        assertEquals(0f, transparent.base.a, 0f)
        val opaque = panel(TokyoNight, opaque = true)
        assertEquals(1f, opaque.base.a, 0f)
        assertEquals(TokyoNight.background.toSkyRgba().r, opaque.base.r, 0f)
    }

    /**
     * The whole panel draws, not only its top: stars are lit in the sidebar column and in
     * the chat column, far below the header's brand row.
     */
    @Test
    fun starsAreLitBehindTheChatAndBehindTheSidebar() {
        for (palette in listOf(TokyoNight, CatppuccinLatte)) {
            val u = panel(palette, energy = 0f, clock = 31.4f)
            val sidebar = litPoints(u, x0 = 0f, x1 = 280f)
            val chat = litPoints(u, x0 = 300f, x1 = width)
            assertTrue("sidebar column: $sidebar lit points", sidebar > 20)
            assertTrue("chat column: $chat lit points", chat > 20)
        }
    }

    /** No fade at the bottom edge: the field reaches the last row of the panel. */
    @Test
    fun theFieldReachesTheBottomOfThePanel() {
        val u = panel(TokyoNight, energy = 0f, clock = 31.4f)
        assertEquals(1f, SkyMath.edge(u.fade, 1f), 0f)
        assertTrue("bottom band: ${litPoints(u, 0f, width, 740f, height)}", litPoints(u, 0f, width, 740f, height) > 4)
    }

    /** The Canvas fallback covers the same region; it is what software GPUs draw. */
    @Test
    fun canvasPlanCoversTheWholePanel() {
        val plan = SkyCanvasPlan.build(panel(TokyoNight, energy = 0f, clock = 31.4f))
        val bottom = plan.stars.filter { it.y > 500f }
        val left = plan.stars.filter { it.x < 280f }
        val right = plan.stars.filter { it.x > 300f }
        assertTrue("${bottom.size} stars below the header", bottom.size > 4)
        assertTrue("${left.size} stars in the sidebar column", left.size > 10)
        assertTrue("${right.size} stars in the chat column", right.size > 3)
        // The base is the theme surface, so the fallback paints no rectangle of its own.
        assertEquals(0f, plan.base.a, 0f)
    }

    /** Comets, when the panel has them, cross the chat too and not only the sidebar. */
    @Test
    fun cometsCrossTheWholePanel() {
        var inChat = 0
        for (i in 0..600) {
            val clock = i * 0.05f
            for (c in SkyCanvasPlan.build(panel(TokyoNight, energy = 1f, clock = clock)).comets) {
                if (c.headX > 300f) inChat++
            }
        }
        assertTrue("comets reaching the chat column: $inChat", inChat > 0)
    }

    /** Points of the shaded frame with visible star light (alpha), in a band of the panel. */
    private fun litPoints(
        u: SkyUniforms,
        x0: Float = 0f,
        x1: Float = width,
        y0: Float = 300f,
        y1: Float = height,
    ): Int {
        var lit = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                if (SkyMath.shade(u, x, y)[3] > 0.06f) lit++
                x += 1f
            }
            y += 1f
        }
        return lit
    }
}
