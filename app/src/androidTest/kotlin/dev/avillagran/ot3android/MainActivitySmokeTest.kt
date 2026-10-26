package dev.avillagran.ot3android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun aFreshInstallStartsOnHomeOfferingAddEnvironment() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(HomeTags.NO_ENVIRONMENTS).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.environments_add)).performClick()
        composeRule.onNodeWithTag(ConnectScreenTags.URL).assertIsDisplayed()
        composeRule.onNodeWithTag(ConnectScreenTags.TOKEN).assertIsDisplayed()
    }
    @Test fun productionDeepLinkKeepsSidebarReachableWithoutLoadedThread() {
        composeRule.activityRule.scenario.onActivity {
            it.startActivity(android.content.Intent(it, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP).setData(android.net.Uri.parse("ot3a://thread/missing-environment/missing-thread")))
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("thread_sidebar_toggle").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("thread_sidebar_toggle").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(HomeTags.NO_ENVIRONMENTS).assertIsDisplayed()
        composeRule.onNodeWithTag("thread_sidebar_toggle").performClick()
        composeRule.onNodeWithTag(dev.avillagran.ot3android.ui.thread.ThreadScreenTags.LOADING).assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("thread_sidebar_toggle").assertIsDisplayed()
    }

}
