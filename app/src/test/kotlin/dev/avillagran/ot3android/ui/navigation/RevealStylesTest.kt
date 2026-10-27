package dev.avillagran.ot3android.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.data.prefs.RevealStyle
import dev.avillagran.ot3android.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [28])
class RevealStylesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun bothAxesUseSpringFallbackAndSnapImmediatelyWhenInstantInterruptsSameTarget() {
        var shown by mutableStateOf(false)
        var style by mutableStateOf(RevealStyle.NEBULA)
        rule.setContent { Ot3Theme(motion = Ot3MotionPolicy(Ot3MotionLevel.FULL, revealStyle = style)) {
            Column {
                SidebarReveal(shown, Modifier.height(100.dp).testTag("horizontal")) { Box(Modifier.fillMaxSize().testTag("sidebarChild")) }
                VerticalReveal(shown, Modifier.testTag("vertical")) { Box(Modifier.width(200.dp).height(80.dp).testTag("verticalChild")) }
            }
        } }
        rule.mainClock.autoAdvance = false
        for (selected in listOf(RevealStyle.NEBULA, RevealStyle.WARP, RevealStyle.FOLD, RevealStyle.SPRING)) {
            rule.runOnUiThread { style = selected; shown = true }
            repeat(6) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
            val width = rule.onNodeWithTag("horizontal").fetchSemanticsNode().boundsInRoot.width
            val height = rule.onNodeWithTag("vertical").fetchSemanticsNode().boundsInRoot.height
            val fullWidth = with(rule.density) { 280.dp.toPx() }
            val fullHeight = with(rule.density) { 80.dp.toPx() }
            assertTrue("API28 $selected must be between endpoints, width=$width", width > 0f && width < fullWidth)
            assertTrue("API28 $selected must be between endpoints, height=$height", height > 0f && height < fullHeight)
            rule.runOnUiThread { style = RevealStyle.INSTANT }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            rule.onNodeWithTag("horizontal").assertWidthIsEqualTo(280.dp)
            rule.onNodeWithTag("vertical").assertHeightIsEqualTo(80.dp)
            rule.onNodeWithTag("verticalChild").assertWidthIsEqualTo(200.dp)
            rule.runOnUiThread { style = selected; shown = false }
            repeat(6) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
            rule.runOnUiThread { style = RevealStyle.INSTANT }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            rule.onNodeWithTag("horizontal").assertWidthIsEqualTo(0.dp)
            rule.onNodeWithTag("vertical").assertHeightIsEqualTo(0.dp)
            rule.onNodeWithTag("sidebarChild").assertDoesNotExist()
            rule.onNodeWithTag("verticalChild").assertDoesNotExist()
        }
    }

    @Test fun nebulaOpensItsGapBeforeDissolvingWhileOtherStylesUseExtent() {
        assertEquals(0.5f, revealReach(RevealStyle.NEBULA, 0.3f), 0.00001f)
        assertEquals(1f, revealReach(RevealStyle.NEBULA, 0.6f), 0f)
        for (style in RevealStyle.entries - RevealStyle.NEBULA) assertEquals(0.3f, revealReach(style, 0.3f), 0f)
        for (style in RevealStyle.entries) {
            assertEquals(0f, revealReach(style, -0.1f), 0f)
            assertEquals(1f, revealReach(style, 1.1f), 0f)
        }
    }
}
