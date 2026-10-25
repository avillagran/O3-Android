package dev.avillagran.ot3android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.connect.ConnectScreenTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real activity, application and graph, and checks the first screen. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchShowsConnectScreen() {
        composeRule.onNodeWithTag(ConnectScreenTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithTag(ConnectScreenTags.CONTINUE).assertIsDisplayed()
    }
}
