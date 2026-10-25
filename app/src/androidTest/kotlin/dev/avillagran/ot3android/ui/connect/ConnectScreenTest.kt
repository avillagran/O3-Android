package dev.avillagran.ot3android.ui.connect

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun connectScreenIsShown() {
        composeRule.setContent {
            Ot3Theme { ConnectScreen(onContinue = {}) }
        }

        composeRule.onNodeWithTag(ConnectScreenTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithTag(ConnectScreenTags.CONTINUE).assertIsDisplayed()
    }
}
