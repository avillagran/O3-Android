package dev.avillagran.ot3android.ui.settings.providers

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ProviderInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun openingBrowserDoesNotAutomaticallyConsent() {
        val interaction = JSONObject("""{"type":"browser","url":"https://auth.test/sign-in","requiresConsent":true}""")
        var opened = false
        var response: JSONObject? = null
        rule.setContent { Ot3Theme { Column { ProviderInteraction(interaction, true, { opened = true }, { response = it }) } } }
        rule.onNodeWithText("Open sign-in page").performClick()
        rule.runOnIdle { assertTrue(opened); assertNull(response) }
        rule.onNodeWithText("Continue").performClick()
        rule.runOnIdle { assertEquals("accept", response?.getString("action")) }
    }
    @Test fun unsafeBrowserUrlCannotOpen() {
        val interaction = JSONObject("""{"type":"deviceCode","url":"file:///etc/passwd","userCode":"CODE"}""")
        rule.setContent { Ot3Theme { Column { ProviderInteraction(interaction, true, { error("unsafe URL") }, {}) } } }
        rule.onNodeWithText("Open sign-in page").assertIsNotEnabled()
        rule.onNodeWithText("CODE").assertExists()
    }
    @Test fun credentialsAreClearedOnSubmissionAndNotEchoedInPlainText() {
        val interaction = JSONObject("""{"type":"credentials","fields":[{"name":"token","label":"Token","secret":true}]}""")
        var response: JSONObject? = null
        rule.setContent { Ot3Theme { Column { ProviderInteraction(interaction, true, {}, { response = it }) } } }
        rule.onNodeWithText("Token").performTextInput("credential")
        rule.onNodeWithText("Continue").performClick()
        rule.runOnIdle { assertEquals("credential", response?.getJSONObject("values")?.getString("token")) }
        rule.onNodeWithText("Token").assertTextEquals("Token", "")
    }
}
