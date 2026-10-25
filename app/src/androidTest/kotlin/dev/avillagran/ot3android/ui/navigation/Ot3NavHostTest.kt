package dev.avillagran.ot3android.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.connect.ConnectScreenTags
import dev.avillagran.ot3android.ui.environments.EnvironmentsScreenTags
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ot3NavHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun connectIsAValidStartDestination() {
        composeRule.setContent { Ot3Theme { Ot3NavHost(startDestination = Routes.CONNECT) } }

        composeRule.onNodeWithTag(ConnectScreenTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithTag(ConnectScreenTags.URL).assertIsDisplayed()
    }

    @Test
    fun environmentsIsAValidStartDestination() {
        composeRule.setContent { Ot3Theme { Ot3NavHost(startDestination = Routes.ENVIRONMENTS) } }

        composeRule.onNodeWithTag(EnvironmentsScreenTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithTag(EnvironmentsScreenTags.ADD).assertIsDisplayed()
    }
}
