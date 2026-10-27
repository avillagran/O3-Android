package dev.avillagran.ot3android.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.avillagran.ot3android.MainActivity
import dev.avillagran.ot3android.Ot3Application
import dev.avillagran.ot3android.core.data.prefs.AppearanceMode
import dev.avillagran.ot3android.ui.navigation.NavTags
import dev.avillagran.ot3android.ui.navigation.TopTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class OmarchyThemeSelectionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun choosingPaletteAppliesImmediatelyAndSurvivesActivityRecreation(): Unit = runBlocking {
        val prefs = (rule.activity.application as Ot3Application).graph.themePreferences
        val previous = prefs.settings.first()
        try {
            prefs.setFollowEnvironment(true)
            rule.onNodeWithTag(NavTags.tab(TopTab.Settings)).performClick()
            rule.onNodeWithTag(SettingsTags.entry(SettingsEntry.Appearance)).performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithTag(AppearanceSettingsTags.light("catppuccin-latte")).fetchSemanticsNodes().isNotEmpty() }
            // Editing the fallback must preserve desktop following.
            rule.onNodeWithTag(AppearanceSettingsTags.light("catppuccin-latte")).performScrollTo().performClick()
            rule.waitUntil(5_000) { runBlocking { prefs.settings.first().manual.mode == AppearanceMode.LIGHT } }
            assertTrue(prefs.settings.first().followEnvironment)
            rule.onNodeWithTag("appearance_phone").performScrollTo().performClick()
            rule.waitUntil(5_000) { runBlocking { val s = prefs.settings.first(); !s.followEnvironment && s.manual.mode == AppearanceMode.LIGHT } }
            assertEquals("catppuccin-latte", prefs.settings.first().manual.lightThemeId)
            rule.activityRule.scenario.recreate()
            rule.onNodeWithTag(AppearanceSettingsTags.light("catppuccin-latte")).performScrollTo().assertIsSelected()
            rule.onNodeWithTag(AppearanceSettingsTags.dark("tokyo-night")).performScrollTo().performClick()
            rule.waitUntil(5_000) { runBlocking { prefs.settings.first().manual.mode == AppearanceMode.DARK } }
            assertFalse(prefs.settings.first().followEnvironment)
            rule.onNodeWithTag(AppearanceSettingsTags.light("catppuccin-latte")).assertIsNotSelected()
            rule.onNodeWithTag(AppearanceSettingsTags.FOLLOW).performScrollTo().performClick()
            rule.waitUntil(5_000) { runBlocking { prefs.settings.first().followEnvironment } }
        } finally {
            prefs.setDarkTheme(previous.manual.darkThemeId)
            prefs.setLightTheme(previous.manual.lightThemeId)
            prefs.setMode(previous.manual.mode)
            prefs.setFollowEnvironment(previous.followEnvironment)
        }
    }
}
