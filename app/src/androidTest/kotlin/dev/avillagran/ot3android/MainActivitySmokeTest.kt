package dev.avillagran.ot3android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.connect.ConnectScreenTags
import dev.avillagran.ot3android.ui.home.HomeTags
import kotlinx.coroutines.flow.first
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
    @Test fun productionDeepLinkKeepsSidebarReachableWithoutLoadedThread() {
        val preferences = composeRule.activity.appGraph.appearancePreferences
        val previous = kotlinx.coroutines.runBlocking { preferences.settings.first().animation }
        try {
            // Access/layout coverage is deterministic even if a previous manual capture left
            // the emulator landscape. Animated effects are covered separately by fixture evidence.
            kotlinx.coroutines.runBlocking {
                composeRule.activity.appGraph.appearancePreferences.setAnimation(
                    dev.avillagran.ot3android.core.data.prefs.AnimationPreference.OFF)
            }
            composeRule.activityRule.scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            composeRule.waitUntil(5_000) {
                composeRule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            }
            composeRule.activityRule.scenario.onActivity {
                it.startActivity(android.content.Intent(it, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP).setData(android.net.Uri.parse("ot3a://thread/missing-environment/missing-thread")))
            }
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag("thread_sidebar_toggle").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("thread_sidebar_toggle").assertIsDisplayed().performClick()
            // Opening the drawer mounts Home and starts its asynchronous Room/domain state.
            // A clickable workspace control does not imply that content is laid out yet.
            composeRule.waitUntil(10_000) {
                composeRule.onNodeWithTag(HomeTags.NO_ENVIRONMENTS).isDisplayed()
            }
            composeRule.onNodeWithTag(HomeTags.NO_ENVIRONMENTS).assertIsDisplayed()
            composeRule.onNodeWithTag("thread_sidebar_toggle").performClick()
            composeRule.onNodeWithTag(dev.avillagran.ot3android.ui.thread.ThreadScreenTags.LOADING).assertIsDisplayed()
            composeRule.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("thread_sidebar_toggle").assertIsDisplayed()
        } finally {
            composeRule.activityRule.scenario.onActivity { it.finish() }
            kotlinx.coroutines.runBlocking { preferences.setAnimation(previous) }
        }
    }

}
