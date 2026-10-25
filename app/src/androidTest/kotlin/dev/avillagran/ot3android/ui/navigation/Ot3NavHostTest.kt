package dev.avillagran.ot3android.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.connect.ConnectScreenTags
import dev.avillagran.ot3android.ui.sidebar.SidebarScreenTags
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ot3NavHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun continueOpensTheSidebarAndPopsConnect() {
        composeRule.setContent {
            Ot3Theme { Ot3NavHost() }
        }

        composeRule.onNodeWithTag(ConnectScreenTags.CONTINUE).performClick()

        composeRule.onNodeWithTag(SidebarScreenTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithTag(ConnectScreenTags.ROOT).assertDoesNotExist()
    }
}
