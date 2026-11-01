package dev.avillagran.ot3android.ui.settings.about

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.testing.ScreenshotTest
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [36], qualifiers = "ar-rSA-w320dp-h640dp-port-xhdpi")
class AboutScreenshotTest : ScreenshotTest() {
    @Test fun rtlLargeTextAbout() {
        snapshot("about-rtl-2") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                AboutScreen({})
            }
        }
        composeRule.onNodeWithContentDescription("O3 Code").assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach {
            assertTrue("touch height ${it.boundsInRoot}", it.boundsInRoot.height >= 96f)
        }
    }
    @Test fun rtlLicenses() {
        snapshot("licenses-rtl") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { AboutScreen({}, true) }
        }
        composeRule.onNodeWithText("O3 Android").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("MIT License", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("MIT License", substring = true).assertExists()
    }
    @Test fun rtlLargeTextLicenses() {
        snapshot("licenses-rtl-2") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                AboutScreen({}, true)
            }
        }
        composeRule.onNodeWithText("O3 Android").assertIsDisplayed()
    }
    @Test fun settingsListsAboutAndLicenses() {
        var opened: dev.avillagran.ot3android.ui.settings.SettingsEntry? = null
        composeRule.setContent {
            dev.avillagran.ot3android.ui.settings.SettingsScreen(onOpen = { opened = it })
        }
        composeRule.onNodeWithTag("settings_About").performScrollTo().performClick()
        assertEquals(dev.avillagran.ot3android.ui.settings.SettingsEntry.About, opened)
        composeRule.onNodeWithTag("settings_Licenses").performScrollTo().performClick()
        assertEquals(dev.avillagran.ot3android.ui.settings.SettingsEntry.Licenses, opened)
    }
    @Test fun interactivePrBadgeHasLabelAndMinimumBounds() {
        var clicks = 0
        snapshot("pr-target") {
            dev.avillagran.ot3android.ui.kit.PrBadge(
                dev.avillagran.ot3android.ui.kit.PrBadgeModel(dev.avillagran.ot3android.ui.kit.PrState.OPEN, "#19"),
                onClick = { clicks++ })
        }
        val button = composeRule.onNode(hasClickAction())
        button.assertContentDescriptionContains("#19", substring = true).performClick()
        assertEquals(1, clicks)
        val bounds = button.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height >= 96f && bounds.width >= 96f)
    }
    @Test fun packagedNoticesAndSearch() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val entries = BundledLicenses.load(context)
        assertTrue(entries.size >= 7)
        assertEquals(entries.size, entries.map { it.file }.distinct().size)
        entries.forEach {
            assertTrue(it.source.startsWith("https://"))
            assertTrue(context.assets.open("licenses/${it.file}").bufferedReader().use { it.readText() }.isNotBlank())
        }
        assertEquals(setOf("Fira Code", "FiraCode Nerd Font (preview glyph subset)"), BundledLicenses.filter(entries, "FIRA ofl").map { it.name }.toSet())
        assertTrue(BundledLicenses.filter(entries, "no-such-license").isEmpty())
    }
}
