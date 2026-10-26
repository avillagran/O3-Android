package dev.avillagran.ot3android.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3MotionPolicy
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class VerticalRevealTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun reducedMotionSnapsWithoutReservingClosedPanelSpace() {
        var shown by mutableStateOf(false)
        rule.setContent { Ot3Theme(motion = Ot3MotionPolicy.Off) {
            VerticalReveal(shown, Modifier.testTag("reveal")) {
                Box(Modifier.width(200.dp).height(80.dp).testTag("natural_child"))
            }
        } }
        rule.onNodeWithTag("reveal").assertHeightIsEqualTo(0.dp)
        rule.onNodeWithTag("natural_child").assertDoesNotExist()
        val started = rule.mainClock.currentTime
        rule.runOnIdle { shown = true }
        rule.waitForIdle()
        rule.onNodeWithTag("reveal").assertHeightIsEqualTo(80.dp)
        rule.onNodeWithTag("natural_child").assertWidthIsEqualTo(200.dp)
        assertTrue("Reduced motion must not run the 520/560ms reveal", rule.mainClock.currentTime - started < 128)
        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        rule.onNodeWithTag("reveal").assertHeightIsEqualTo(0.dp)
    }
}
