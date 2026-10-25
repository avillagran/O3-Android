package dev.avillagran.ot3android.ui.theme

import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class Ot3ColorSchemeTest {
    private val catalog = OmarchyThemeCatalog.loadBlocking(ApplicationProvider.getApplicationContext())

    @Test
    fun `the Material scheme follows tokens_md section 2`() {
        val c = catalog.defaultColors(dark = true)
        val s = c.toColorScheme()
        assertEquals(c.accent, s.primary)
        assertEquals(c.onAccent, s.onPrimary)
        assertEquals(c.accentContainer, s.primaryContainer)
        assertEquals(c.accent, s.onPrimaryContainer)
        assertEquals(c.input, s.secondary)
        assertEquals(c.accentContainer, s.secondaryContainer)
        assertEquals(c.foreground, s.onSecondaryContainer)
        assertEquals(c.waiting, s.tertiary)
        assertEquals(c.failed, s.error)
        assertEquals(c.failedContainer, s.errorContainer)
        assertEquals(c.background, s.background)
        assertEquals(c.background, s.surface)
        assertEquals(c.foreground, s.onSurface)
        assertEquals(c.surface, s.surfaceVariant)
        assertEquals(c.dim, s.onSurfaceVariant)
        assertEquals(c.background, s.surfaceContainerLowest)
        assertEquals(c.changesCard, s.surfaceContainerLow)
        assertEquals(c.surface, s.surfaceContainer)
        assertEquals(c.rowSelected, s.surfaceContainerHigh)
        assertEquals(c.cardBorder, s.surfaceContainerHighest)
        assertEquals(c.cardBorder, s.outline)
        assertEquals(c.divider, s.outlineVariant)
        assertEquals(c.foreground, s.inverseSurface)
        assertEquals(Color.Black, s.scrim)
        assertEquals(Color.Transparent, s.surfaceTint)
    }

    @Test
    @Config(sdk = [36])
    fun `Material You keeps the default theme's status colours`() {
        val defaults = catalog.defaultColors(dark = true)
        val scheme = dynamicDarkColorScheme(ApplicationProvider.getApplicationContext())
        val colors = materialYouColors(scheme, defaults)
        assertEquals(Ot3Colors.MATERIAL_YOU_ID, colors.themeId)
        assertEquals(scheme.primary, colors.accent)
        assertEquals(scheme.surface, colors.background)
        assertEquals(scheme.onSurface, colors.foreground)
        assertEquals(defaults.failed, colors.failed)
        assertEquals(defaults.working, colors.working)
        assertEquals(Ot3Blend.over(colors.foreground, colors.background, 570), colors.dim)
        assertFalse(colors.isLight)
    }

    @Test
    fun `blending rounds exact ties to even, as the importer does`() {
        val tokyoFg = Ot3Colors.rgb(0xa9b1d6)
        val tokyoBg = Ot3Colors.rgb(0x1a1b26)
        assertEquals(Ot3Colors.rgb(0x272836), Ot3Blend.over(tokyoFg, tokyoBg, 90))
        assertEquals(Ot3Colors.rgb(0xe3e5ea), Ot3Blend.over(Ot3Colors.rgb(0x4c4f69), Ot3Colors.rgb(0xeff1f5), 75))
    }
}
