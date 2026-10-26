package dev.avillagran.ot3android.ui.navigation

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ThreadWorkspaceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun phoneRotationCollapseAndRestorationKeepChatCompositionAndDraft() {
        var landscape by mutableStateOf(true)
        var starts = 0
        var stops = 0
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                smallestScreenWidthDp = 411
            }
            CompositionLocalProvider(LocalConfiguration provides config) {
                dev.avillagran.ot3android.ui.theme.Ot3Theme {
                ThreadWorkspace(sidebar = { Text("Host node · feat/example", Modifier.testTag("sidebar_content")) }) {
                    androidx.compose.foundation.layout.Column {
                    LocalThreadSidebarControl.current?.invoke()
                    DisposableEffect(Unit) { starts++; onDispose { stops++ } }
                    var draft by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(draft, { draft = it }, Modifier.testTag("draft"))
                    }
                }
                }
            }
        }
        rule.onNodeWithTag("draft").performTextInput("Borrador persistente")
        rule.onNodeWithTag("thread_sidebar_toggle").performClick()
        rule.onNodeWithTag("sidebar_content").assertDoesNotExist()
        rule.runOnIdle { landscape = false }
        rule.onNodeWithTag("thread_sidebar_toggle").assertDoesNotExist()
        rule.runOnIdle { landscape = true }
        rule.onNodeWithTag("sidebar_content").assertDoesNotExist()
        rule.onNodeWithTag("draft").assertTextContains("Borrador persistente")
        rule.runOnIdle { assertEquals(1, starts); assertEquals(0, stops) }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("sidebar_content").assertDoesNotExist()
        rule.onNodeWithTag("draft").assertTextContains("Borrador persistente")
        rule.onNodeWithTag("thread_sidebar_toggle").performClick()
        rule.onNodeWithTag("sidebar_content").assertIsDisplayed()
    }
}
