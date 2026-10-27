package dev.avillagran.ot3android.ui.sky

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.home.HomeContent
import dev.avillagran.ot3android.core.home.HomeUiState
import dev.avillagran.ot3android.ui.home.HomeScreen
import dev.avillagran.ot3android.ui.home.HomeTags
import dev.avillagran.ot3android.ui.navigation.ThreadWorkspace
import dev.avillagran.ot3android.ui.theme.Ot3MotionPolicy
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The sky as pixels in the regions it has to cover (UI_SPEC 3.5): behind the chat and
 * behind the whole sidebar, in landscape and in the phone's drawer. These checks read the
 * background itself on a real graphics stack, so they cannot pass because the screen
 * happens to contain the expected text or buttons.
 *
 * Motion is Off: one static frame of the same field the device animates.
 */
@RunWith(AndroidJUnit4::class)
class SkyRegionsDeviceTest {
    @get:Rule val rule = createComposeRule()

    /** Sparse on purpose: the measured bands hold no text or controls. */
    @androidx.compose.runtime.Composable
    private fun SidebarFixture() {
        Column(Modifier.fillMaxSize().testTag("fixture_sidebar")) {
            Spacer(Modifier.fillMaxWidth().height(160.dp))
        }
    }

    @Test
    fun thePanelSkyCoversTheChatAndTheSidebarInLandscape() {
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 891
                screenHeightDp = 411
                smallestScreenWidthDp = 411
            }
            CompositionLocalProvider(LocalConfiguration provides config) {
                Ot3Theme(motion = Ot3MotionPolicy.Off) {
                    ThreadWorkspace(sidebar = { SidebarFixture() }) {
                        Box(Modifier.fillMaxSize().testTag("fixture_chat"))
                    }
                }
            }
        }
        rule.onNodeWithTag("workspace_sky").assertExists()
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        // Sidebar: 280 of 411 dp; the chat takes the rest. Below the sidebar header.
        val sidebarStars = edges(pixels, 0.04, 0.45, 0.60, 0.92)
        val chatStars = edges(pixels, 0.72, 0.45, 0.98, 0.92)
        assertTrue("stars behind the sidebar list (edges=$sidebarStars)", sidebarStars > 10)
        assertTrue("stars behind the chat (edges=$chatStars)", chatStars > 10)
    }

    @Test
    fun thePortraitDrawerShowsTheSameFieldAndHidesTheChatBehindIt() {
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = 411
                screenHeightDp = 891
                smallestScreenWidthDp = 411
            }
            CompositionLocalProvider(LocalConfiguration provides config) {
                Ot3Theme(motion = Ot3MotionPolicy.Off) {
                    ThreadWorkspace(sidebar = { SidebarFixture() }) {
                        // A colour no sky, theme or row uses: if any of it shows through the
                        // drawer, the drawer is not covering the chat.
                        Box(Modifier.fillMaxSize().background(Color.Magenta).testTag("fixture_chat"))
                    }
                }
            }
        }
        rule.onNodeWithTag("thread_sidebar_toggle").performClick()
        rule.onNodeWithTag("thread_sidebar_drawer").assertExists()
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val drawerStars = edges(pixels, 0.04, 0.30, 0.58, 0.92)
        assertTrue("stars behind the drawer (edges=$drawerStars)", drawerStars > 10)
        // The drawer is 280 of 411 dp wide.
        var magenta = 0
        for (y in 0 until pixels.height step 3) for (x in 0 until (pixels.width * 0.58f).toInt() step 3) {
            val c = pixels[x, y]
            if (c.red > 0.9f && c.green < 0.1f && c.blue > 0.9f) magenta++
        }
        assertEquals("the drawer must hide the chat", 0, magenta)
    }

    /** Home alone owns the panel sky too (`ot3:ui/qml/T3Panel.qml:842-856`). */
    @Test
    fun homeDrawsThePanelSkyBehindItsList() {
        rule.setContent {
            Ot3Theme(motion = Ot3MotionPolicy.Off) {
                HomeScreen(HomeUiState(content = HomeContent.Empty), true, {})
            }
        }
        rule.onNodeWithTag(HomeTags.SKY).assertExists()
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val listStars = edges(pixels, 0.06, 0.60, 0.94, 0.90)
        assertTrue("stars behind the Home list (edges=$listStars)", listStars > 10)
    }

    /** Horizontally neighbouring pixels whose colour changes: stars, not a gradient. */
    private fun edges(pixels: PixelMap, fx0: Double, fy0: Double, fx1: Double, fy1: Double): Int {
        val x0 = (pixels.width * fx0).toInt()
        val x1 = (pixels.width * fx1).toInt()
        val y0 = (pixels.height * fy0).toInt()
        val y1 = (pixels.height * fy1).toInt()
        var count = 0
        for (y in y0 until y1 step 2) {
            for (x in x0 until x1 - 2 step 2) {
                val a = pixels[x, y]
                val b = pixels[x + 2, y]
                if (abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) > 0.035f) count++
            }
        }
        return count
    }
}
