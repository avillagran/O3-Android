package dev.avillagran.ot3android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.avillagran.ot3android.core.theme.ThemeTokens
import dev.avillagran.ot3android.testing.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The app's colours against the lib's `theme.tokens` (host JNI library). Plain JUnit, not
 * Robolectric: each Robolectric sandbox has its own class loader, and a JNI library loads in
 * one class loader only.
 */
class ThemeTokensAppTest {

    private val catalog = OmarchyThemeCatalog.parse(File("src/main/assets/${OmarchyThemeCatalog.ASSET_PATH}").readText())

    @Before
    fun setUp() = assumeHostJni()

    @Test
    fun `the bundled themes are the lib's theme_tokens for their colors_toml`() = runBlocking {
        // The two Omarchy files core's tests keep (core/src/test/resources/omarchy).
        for (id in listOf("tokyo-night", "catppuccin-latte")) {
            val text = File("../core/src/test/resources/omarchy/$id/colors.toml").readText()
            val live = ThemeTokens.fromColorsToml(text, id)
            // JSONObject has no equals: compare everything but the raw result.
            val raw = JSONObject()
            assertEquals(id, live.copy(raw = raw), catalog[id]!!.copy(raw = raw))
            assertEquals(id, Ot3Colors.fromTheme(live), Ot3Colors.fromTheme(catalog[id]!!))
        }
    }

    @Test
    fun `Material You keeps the default theme's status colours`() = runBlocking {
        val defaults = catalog.defaultColors(dark = true)
        val scheme = darkColorScheme(primary = Color(0xFFA8C7FA), surface = Color(0xFF131314), onSurface = Color(0xFFE3E3E3), outline = Color(0xFF8E918F))
        val colors = materialYouColors(scheme, catalog.defaultTheme(dark = true))
        assertEquals(Ot3Colors.MATERIAL_YOU_ID, colors.themeId)
        assertEquals(scheme.primary, colors.accent)
        assertEquals(scheme.surface, colors.background)
        assertEquals(scheme.onSurface, colors.foreground)
        assertEquals(scheme.outline, colors.muted)
        assertEquals(defaults.failed, colors.failed)
        assertEquals(defaults.working, colors.working)
        // Every other role is theme.tokens over the wallpaper's colours.
        val tokens = ThemeTokens.fromColorsToml("background = \"${hex(scheme.surface)}\"\nforeground = \"${hex(scheme.onSurface)}\"\n", null)
        assertEquals(Ot3Colors.rgb(tokens.role("dim")), colors.dim)
        assertFalse(colors.isLight)
    }

    private fun hex(color: Color) = "#%06x".format(color.toArgb() and 0xFFFFFF)
}
