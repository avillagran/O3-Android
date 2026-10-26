package dev.avillagran.ot3android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.connect.ConnectScreenTags
import dev.avillagran.ot3android.ui.home.HomeTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real activity, application and graph, and checks the first screen. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appearanceIsReachableFromTheRealSettingsTabAndLoadsBundledThemes() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(HomeTags.NO_ENVIRONMENTS).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(dev.avillagran.ot3android.ui.navigation.NavTags.tab(dev.avillagran.ot3android.ui.navigation.TopTab.Settings)).performClick()
        composeRule.onNodeWithTag(dev.avillagran.ot3android.ui.settings.SettingsTags.entry(dev.avillagran.ot3android.ui.settings.SettingsEntry.Appearance)).performClick()
        val root = dev.avillagran.ot3android.ui.settings.AppearanceSettingsTags.ROOT
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(root).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(dev.avillagran.ot3android.ui.settings.AppearanceSettingsTags.FOLLOW).assertIsDisplayed()
        // Nord is not one of UI-1g's former default/stored/Material You placeholders.
        composeRule.onNodeWithTag(dev.avillagran.ot3android.ui.settings.AppearanceSettingsTags.dark("nord")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aFreshInstallStartsOnHomeOfferingAddEnvironment() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(HomeTags.NO_ENVIRONMENTS).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.environments_add)).performClick()
        composeRule.onNodeWithTag(ConnectScreenTags.URL).assertIsDisplayed()
        composeRule.onNodeWithTag(ConnectScreenTags.TOKEN).assertIsDisplayed()
    }
}
