package dev.avillagran.ot3android.ui.theme

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.data.prefs.AppearanceMode
import dev.avillagran.ot3android.core.data.prefs.AppearanceSettings
import dev.avillagran.ot3android.core.theme.ThemeSelection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LiveThemeApplicationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun liveNativeTokensOverrideManualAppearanceAndFallbackKeepsTheDraftComposition() {
        val catalog = runBlocking { OmarchyThemeCatalog.load(rule.activity) }
        val data = rule.activity.assets.open(OmarchyThemeCatalog.ASSET_PATH).bufferedReader().use { JSONObject(it.readText()) }
        val themes = data.getJSONArray("themes")
        val latte = (0 until themes.length()).map(themes::getJSONObject).first { it.getString("id") == "catppuccin-latte" }
        var live by mutableStateOf<ThemeSelection.Live?>(ThemeSelection.Live("node", "node", "catppuccin-latte", "Catppuccin Latte", "native-latte", latte, false))
        var actual: Ot3Colors? = null
        var starts = 0
        var stops = 0
        rule.setContent {
            Ot3AppTheme(catalog, AppearanceSettings(mode = AppearanceMode.DARK), live) {
                val colors = Ot3Theme.colors
                SideEffect { actual = colors }
                DisposableEffect(Unit) { starts++; onDispose { stops++ } }
                var draft by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(draft, { draft = it }, Modifier.testTag("draft"))
            }
        }
        rule.onNodeWithTag("draft").performTextInput("Keep this draft")
        rule.runOnIdle {
            assertEquals(true, actual!!.isLight)
            assertEquals(catalog["catppuccin-latte"]!!.base.getValue("background"), actual!!.background.toArgb() and 0xFFFFFF)
            live = null
        }
        rule.onNodeWithTag("draft").assertTextContains("Keep this draft")
        rule.runOnIdle {
            assertEquals(false, actual!!.isLight)
            assertEquals("tokyo-night", actual!!.themeId)
            assertEquals(1, starts)
            assertEquals(0, stops)
        }
    }
}
