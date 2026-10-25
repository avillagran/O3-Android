package dev.avillagran.ot3android.testing

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import dev.avillagran.ot3android.ui.theme.OmarchyThemeCatalog
import dev.avillagran.ot3android.ui.theme.Ot3Colors
import dev.avillagran.ot3android.ui.theme.Ot3MotionPolicy
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshot tests (docs/UI_SPEC.md section 5): Robolectric native graphics on a
 * 411 × 891 dp phone, compared by Roborazzi with the goldens in `app/src/test/snapshots/`.
 * `./gradlew :app:recordRoborazziDebug` rewrites them; `:app:verifyRoborazziDebug` (CI)
 * fails on any difference.
 *
 * ```
 * class HomeScreenshotTest : ScreenshotTest() {
 *     @Test fun home() = snapshot("home") { HomeScreen(state = fixture) }
 * }
 * ```
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = ScreenshotTest.PHONE)
abstract class ScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Renders [content] inside [Ot3Theme] on the theme's background, once per theme in
     * [themes], and captures `<Class>/<name>.<theme id>.png`. Motion is Off, so every
     * animation is at its end state.
     */
    fun snapshot(name: String, themes: List<String> = THEMES, content: @Composable () -> Unit) {
        val catalog = OmarchyThemeCatalog.loadBlocking(ApplicationProvider.getApplicationContext())
        var current by mutableStateOf(themes.first())
        composeRule.setContent {
            val theme = checkNotNull(catalog[current]) { "no Omarchy theme $current" }
            Ot3Theme(colors = Ot3Colors.fromTheme(theme), motion = Ot3MotionPolicy.Off) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
        for (id in themes) {
            current = id
            composeRule.waitForIdle()
            composeRule.onRoot().captureRoboImage("${roborazziSystemPropertyOutputDirectory()}/${javaClass.simpleName}/$name.$id.png")
        }
    }

    companion object {
        /** A 411 × 891 dp phone at xhdpi (822 × 1782 px). */
        const val PHONE = "w411dp-h891dp-port-xhdpi"

        /** The default dark and light themes (UI_SPEC section 5). */
        val THEMES = listOf("tokyo-night", "catppuccin-latte")
    }
}
