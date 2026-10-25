package dev.avillagran.ot3android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The committed JSON (tools/import-omarchy-themes) against the goldens of
 * docs/ui/tokens.md section 1, read through the app's own loader.
 */
@RunWith(AndroidJUnit4::class)
class OmarchyThemeCatalogTest {
    private val catalog = OmarchyThemeCatalog.loadBlocking(ApplicationProvider.getApplicationContext())

    @Test
    fun `all 22 Omarchy 4_0_2 themes, 17 dark and 5 light`() {
        assertEquals("4.0.2", catalog.omarchyVersion)
        assertEquals(
            listOf(
                "catppuccin", "catppuccin-latte", "ethereal", "everforest", "flexoki-light", "gruvbox", "hackerman",
                "kanagawa", "last-horizon", "lumon", "lupine", "matte-black", "miasma", "nord", "osaka-jade",
                "retro-82", "ristretto", "rose-pine", "solitude", "tokyo-night", "vantablack", "white",
            ),
            catalog.themes.map { it.id },
        )
        assertEquals(17, catalog.ofAppearance(light = false).size)
        assertEquals(
            listOf("catppuccin-latte", "flexoki-light", "lupine", "rose-pine", "white"),
            catalog.ofAppearance(light = true).map { it.id },
        )
    }

    @Test
    fun `tokyo-night matches the tokens_md goldens`() {
        assertGoldens("tokyo-night", TOKYO_NIGHT)
        assertEquals("Tokyo Night", catalog["tokyo-night"]!!.name)
    }

    @Test
    fun `catppuccin-latte matches the tokens_md goldens`() {
        assertGoldens("catppuccin-latte", CATPPUCCIN_LATTE)
        assertEquals("Catppuccin Latte", catalog["catppuccin-latte"]!!.name)
    }

    @Test
    fun `tokyo-night palette, sky and onAccent match omarchy-themes_md`() {
        val theme = catalog["tokyo-night"]!!
        assertEquals(
            listOf("#7aa2f7", "#9ece6a", "#ad8ee6", "#449dab", "#eb927b", "#e0af68", "#f7768e", "#7da6ff", "#bb9af7"),
            theme.projectPalette.map(::hex),
        )
        assertEquals(Ot3Sky(lightSky = false, brightness = 1.25f, nebula = 0.55f, meteorRate = 0.85f, ink = 1f), theme.sky)
        assertEquals("#1a1b26", hex(theme.role("onAccent")))
        assertEquals(Ot3Sky(lightSky = true, brightness = 0.9f, nebula = 0.55f, meteorRate = 0.85f, ink = 1.7f), catalog["white"]!!.sky)
    }

    @Test
    fun `the runtime formulas reproduce every role of every theme`() {
        // Material You derives roles on the device; it must agree with the importer.
        for (theme in catalog.themes) {
            val imported = Ot3Colors.fromTheme(theme)
            val derived = Ot3Colors.derive(
                themeId = theme.id,
                background = imported.background,
                foreground = imported.foreground,
                accent = imported.accent,
                muted = imported.muted,
                statusFrom = imported,
            )
            assertEquals(theme.id, imported, derived)
            assertEquals(theme.id, theme.isLight, Ot3Colors.isLightBackground(imported.background))
        }
    }

    @Test
    fun `palettes have no duplicates and fall back to the accent`() {
        for (theme in catalog.themes) {
            assertEquals(theme.id, theme.projectPalette.distinct(), theme.projectPalette)
        }
        // white has no orange: ot3's pick() gives the accent, which is not otherwise in its palette.
        val white = catalog["white"]!!
        assertTrue(white.base("accent") in white.projectPalette)
    }

    private fun assertGoldens(id: String, goldens: Map<String, String>) {
        val colors = Ot3Colors.fromTheme(catalog[id]!!)
        val actual = mapOf(
            "background" to colors.background, "foreground" to colors.foreground, "accent" to colors.accent,
            "muted" to colors.muted, "dim" to colors.dim, "divider" to colors.divider, "surface" to colors.surface,
            "card" to colors.card, "cardBorder" to colors.cardBorder, "changesCard" to colors.changesCard,
            "rowSelected" to colors.rowSelected, "rowPressed" to colors.rowPressed, "rowDraft" to colors.rowDraft,
            "field" to colors.field, "fieldBorder" to colors.fieldBorder, "switchOff" to colors.switchOff,
            "inlineCode" to colors.inlineCode, "composerBorder" to colors.composerBorder,
            "composerFocus" to colors.composerFocus, "working" to colors.working, "done" to colors.done,
            "waiting" to colors.waiting, "failed" to colors.failed, "warning" to colors.warning, "input" to colors.input,
        ).mapValues { hex(it.value) }
        assertEquals(goldens, actual)
    }

    private companion object {
        fun hex(rgb: Int) = "#%06x".format(rgb and 0xFFFFFF)
        fun hex(color: Color) = hex(color.toArgb())

        /** docs/ui/tokens.md section 1, column tokyo-night. */
        val TOKYO_NIGHT = mapOf(
            "background" to "#1a1b26", "foreground" to "#a9b1d6", "accent" to "#7aa2f7", "muted" to "#414868",
            "dim" to "#6c708a", "divider" to "#272836", "surface" to "#20222e", "card" to "#1f202c",
            "cardBorder" to "#2b2d3b", "changesCard" to "#1e1f2a", "rowSelected" to "#252633",
            "rowPressed" to "#20222e", "rowDraft" to "#202333", "field" to "#20212d", "fieldBorder" to "#2e303f",
            "switchOff" to "#2f3240", "inlineCode" to "#282a38", "composerBorder" to "#272836",
            "composerFocus" to "#455884", "working" to "#7aa2f7", "done" to "#9ece6a", "waiting" to "#e0af68",
            "failed" to "#f7768e", "warning" to "#eb927b", "input" to "#ad8ee6",
        )

        /** docs/ui/tokens.md section 1, column catppuccin-latte. */
        val CATPPUCCIN_LATTE = mapOf(
            "background" to "#eff1f5", "foreground" to "#4c4f69", "accent" to "#1e66f5", "muted" to "#acb0be",
            "dim" to "#9295a5", "divider" to "#e0e2e8", "surface" to "#e8eaef", "card" to "#e9ebf0",
            "cardBorder" to "#dbdee4", "changesCard" to "#ebedf2", "rowSelected" to "#e3e5ea",
            "rowPressed" to "#e8eaef", "rowDraft" to "#e2e9f5", "field" to "#e8ebef", "fieldBorder" to "#d8dae1",
            "switchOff" to "#d7d9e0", "inlineCode" to "#dfe1e7", "composerBorder" to "#e0e2e8",
            "composerFocus" to "#91b2f5", "working" to "#1e66f5", "done" to "#40a02b", "waiting" to "#df8e1d",
            "failed" to "#d20f39", "warning" to "#d84e2b", "input" to "#ea76cb",
        )
    }
}
