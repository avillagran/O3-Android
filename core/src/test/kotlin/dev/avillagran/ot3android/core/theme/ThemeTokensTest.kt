package dev.avillagran.ot3android.core.theme

import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [ThemeTokens.fromColorsToml] over the lib's `theme.tokens` (host JNI library). */
class ThemeTokensTest {

    @Before
    fun setUp() = assumeHostJni()

    private fun theme(id: String) = runBlocking {
        ThemeTokens.fromColorsToml(javaClass.getResource("/omarchy/$id/colors.toml")!!.readText(), id, DomainEngine.shared)
    }

    private fun hex(rgb: Int) = "#%06x".format(rgb)

    @Test
    fun tokyoNightIsWhatOt3Draws() {
        val tokens = theme("tokyo-night")
        assertEquals("tokyo-night" to "Tokyo Night", tokens.id to tokens.name)
        assertFalse(tokens.isLight)
        assertEquals("Qt's blend, not straight alpha (#6c708a)", "#6b718a", hex(tokens.role("dim")))
        assertEquals("#21222e", hex(tokens.role("surface")))
        assertEquals("#1a1b26", hex(tokens.role("onAccent")))
        assertEquals(tokens.role("statusMuted"), tokens.statusColors.getValue(""))
        assertEquals(tokens.role("warning"), tokens.statusColors.getValue("approval"))
        assertEquals(tokens.projectPalette.size, tokens.projectFills.size)
        assertEquals("#7aa2f7", hex(tokens.projectPalette.first()))
        assertEquals(1.25f, tokens.sky.headerBrightness)
        assertEquals("dark", tokens.mcpTheme)
    }

    @Test
    fun catppuccinLatteIsLight() {
        val tokens = theme("catppuccin-latte")
        assertTrue(tokens.isLight)
        assertTrue(tokens.sky.lightSky)
        assertEquals(1.7f, tokens.sky.ink)
        assertEquals("#9295a6", hex(tokens.role("dim")))
    }

    @Test
    fun aThemeWithoutAColourHasNoBaseEntryForIt() {
        val tokens = runBlocking { ThemeTokens.fromColorsToml("background = \"#000000\"\nforeground = \"#ffffff\"\naccent = \"#ff0000\"\n", null) }
        assertEquals("", tokens.id)
        assertFalse("orange" in tokens.base)
        assertEquals("warning falls back to the accent", tokens.base("accent"), tokens.role("warning"))
    }
}
