package dev.avillagran.ot3android.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.data.prefs.AppearanceSettings
import dev.avillagran.ot3android.core.data.prefs.RevealPace
import dev.avillagran.ot3android.ui.theme.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp-land-xhdpi")
class RevealPaceApplicationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var previousScale = 1f
    @org.junit.Before fun enableMotion() {
        val resolver = rule.activity.contentResolver
        previousScale = android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    @org.junit.After fun restoreMotion() {
        android.provider.Settings.Global.putFloat(rule.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
    }
    @Test fun applicationThemePropagatesPaceToRealWorkspaceWithoutRestartingChat() {
        val catalog = runBlocking { OmarchyThemeCatalog.load(rule.activity) }
        var settings by mutableStateOf(AppearanceSettings())
        var actual = RevealPace.NORMAL
        var duration = 0
        var starts = 0
        var level = Ot3MotionLevel.OFF
        rule.setContent {
            Ot3AppTheme(catalog, settings) {
                val motion = Ot3Theme.motion
                SideEffect { actual = motion.revealPace; level = motion.level; duration = (motion.reveal<Float>() as androidx.compose.animation.core.TweenSpec).durationMillis }
                ThreadWorkspace(sidebar = { Text("Home sidebar") }, chat = {
                    DisposableEffect(Unit) { starts++; onDispose { } }
                    var draft by rememberSaveable { mutableStateOf("Retained draft") }
                    OutlinedTextField(draft, { draft = it }, Modifier.testTag("draft"))
                })
            }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(RevealPace.NORMAL, actual)
        assertEquals(Ot3MotionLevel.FULL, level)
        for (pace in listOf(RevealPace.SLOW, RevealPace.FAST, RevealPace.NORMAL)) {
            rule.runOnIdle { settings = settings.copy(revealPace = pace) }
            rule.waitForIdle()
            assertEquals(pace, actual)
            assertEquals(when (pace) { RevealPace.SLOW -> 832; RevealPace.NORMAL -> 520; RevealPace.FAST -> 312 }, duration)
            rule.onNodeWithTag("thread_sidebar_toggle").performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithTag("draft").assertTextContains("Retained draft")
        assertEquals(1, starts)
    }
}
