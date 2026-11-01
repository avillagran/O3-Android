package dev.avillagran.ot3android.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.environments.EnvironmentsScreenTags
import dev.avillagran.ot3android.ui.home.HomeTags
import dev.avillagran.ot3android.ui.settings.SettingsEntry
import dev.avillagran.ot3android.ui.settings.SettingsTags
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real shell and screens on a device: tabs, Home, Settings → Environments. */
@RunWith(AndroidJUnit4::class)
class Ot3NavHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pairingLinkAtStartupSurvivesChatsGraphRemoval() {
        // This replaces the Chats stack while its Home destination is still composing.
        composeRule.setContent { Ot3Theme { Ot3NavHost(pairingLinkWaiting = true) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(dev.avillagran.ot3android.ui.pair.PairScreenTags.ROOT).assertIsDisplayed()
    }

    @Test
    fun startsOnHomeWithTheThreeTabs() {
        composeRule.setContent { Ot3Theme { Ot3NavHost() } }

        composeRule.onNodeWithTag(HomeTags.ROOT).assertIsDisplayed()
        TopTab.entries.forEach { composeRule.onNodeWithTag(NavTags.tab(it)).assertIsDisplayed() }
    }

    @Test
    fun environmentsLiveUnderSettings() {
        composeRule.setContent { Ot3Theme { Ot3NavHost() } }

        composeRule.onNodeWithTag(NavTags.tab(TopTab.Settings)).performClick()
        composeRule.onNodeWithTag("settings_page_connections").performClick()
        composeRule.onNodeWithTag("connections_environments").performClick()
        composeRule.onNodeWithTag(EnvironmentsScreenTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithTag(EnvironmentsScreenTags.ADD).assertIsDisplayed()
    }
}
