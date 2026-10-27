package dev.avillagran.ot3android.ui.navigation

import android.Manifest
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.core.data.prefs.*
import dev.avillagran.ot3android.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

/** Real Android layers and AGSL when supported; both axes share the selected source style. */
class RevealStylesDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var previousScale = 1f
    @Before fun enableMotion() {
        val resolver = instrumentation.targetContext.contentResolver
        previousScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    @After fun restoreMotion() {
        rule.mainClock.autoAdvance = true
        Settings.Global.putFloat(instrumentation.targetContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }
    @Test fun everyStyleRevealsBothNaturalChildrenAndInstantInterruptsAnActiveTransition() {
        var shown by mutableStateOf(false)
        var style by mutableStateOf(RevealStyle.NEBULA)
        var preference by mutableStateOf(AnimationPreference.FULL)
        var observed: RevealState? = null
        var hardware = false
        rule.setContent { Ot3Theme(motion = rememberOt3Motion(preference, RevealPace.NORMAL, style)) {
            val probe = rememberRevealState(shown, "capabilityProbe")
            val view = LocalView.current
            SideEffect { observed = probe; hardware = view.isHardwareAccelerated }
            Regions(shown)
        } }
        rule.mainClock.autoAdvance = false
        for (selected in RevealStyle.entries) {
            rule.runOnUiThread { style = selected; shown = true }
            frames(10)
            val state = checkNotNull(observed)
            val expected = if (selected == RevealStyle.INSTANT) selected else if (state.effect == null) RevealStyle.SPRING else selected
            assertEquals(expected, state.style)
            if (selected == RevealStyle.SPRING || selected == RevealStyle.INSTANT) assertNull(state.shader)
            android.util.Log.i("RevealStyleEvidence", "selected=$selected effective=${state.style} hardware=$hardware shader=${state.shader != null} progress=${state.progress}")
            if (selected != RevealStyle.INSTANT) {
                val width = rule.onNodeWithTag("reveal_horizontal").fetchSemanticsNode().boundsInRoot.width
                val height = rule.onNodeWithTag("reveal_vertical").fetchSemanticsNode().boundsInRoot.height
                assertTrue("Both axes must be present during $selected", width > 0f && height > 0f)
            }
            rule.runOnUiThread { style = RevealStyle.INSTANT }
            frames(1)
            rule.onNodeWithTag("reveal_horizontal").assertWidthIsEqualTo(280.dp)
            rule.onNodeWithTag("reveal_vertical").assertHeightIsEqualTo(120.dp)
            val instantPixels = rule.onRoot().captureToImage().toPixelMap()
            rule.runOnUiThread { style = selected }
            frames(1)
            val settledPixels = rule.onRoot().captureToImage().toPixelMap()
            for (y in 0 until instantPixels.height step 8) for (x in 0 until instantPixels.width step 8) {
                assertEquals("Settled $selected must not retain an effect", instantPixels[x, y], settledPixels[x, y])
            }
            rule.runOnUiThread { shown = false }
            frames(6)
            // Off also cuts an unchanged target, including a running shader reveal.
            rule.runOnUiThread { preference = AnimationPreference.OFF }
            frames(1)
            rule.onNodeWithTag("reveal_horizontal").assertWidthIsEqualTo(0.dp)
            rule.onNodeWithTag("reveal_vertical").assertHeightIsEqualTo(0.dp)
            assertEquals(RevealStyle.INSTANT, observed?.style)
            assertNull(observed?.shader)
            rule.runOnUiThread { preference = AnimationPreference.FULL }
            frames(1)
        }
    }
    @Test fun shadedStylesRenderVisibleContentAtAnIntermediateProgressOnBothAxes() {
        var shown by mutableStateOf(false)
        var selected by mutableStateOf(RevealStyle.NEBULA)
        var actual: RevealState? = null
        rule.setContent { Ot3Theme(motion = rememberOt3Motion(AnimationPreference.FULL, RevealPace.NORMAL, selected)) {
            val state = rememberRevealState(shown, "visibleShaderProbe")
            SideEffect { actual = state }
            Regions(shown)
        } }
        rule.waitForIdle()
        org.junit.Assume.assumeTrue("Requires Android AGSL", actual?.effect != null)
        rule.mainClock.autoAdvance = false
        for (style in listOf(RevealStyle.NEBULA, RevealStyle.WARP, RevealStyle.FOLD)) {
            rule.runOnUiThread { selected = style; shown = true }
            frames(20)
            val progress = checkNotNull(actual).progress
            assertTrue("$style must still be moving, progress=$progress", progress > 0f && progress < 1f)
            for (axis in listOf("horizontal", "vertical")) {
                val pixels = rule.onNodeWithTag("reveal_$axis").captureToImage().toPixelMap()
                var colored = 0
                for (y in 0 until pixels.height step 2) for (x in 0 until pixels.width step 2) {
                    val color = pixels[x, y]
                    if (color.blue > 0.35f && color.blue - color.red > 0.12f) colored++
                }
                android.util.Log.i("RevealStyleEvidence", "visibleContent style=$style axis=$axis progress=$progress coloredPixels=$colored")
                assertTrue("$style $axis must render the colored marker during motion, pixels=$colored", colored > 12)
            }
            rule.runOnUiThread { shown = false }
            frames(40)
        }
    }

    @Test fun evidenceWarp() = evidence(RevealStyle.WARP)
    @Test fun evidenceFold() = evidence(RevealStyle.FOLD)
    private fun evidence(style: RevealStyle) {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("revealStyleEvidence") == "true")
        var shown by mutableStateOf(true)
        var actual: RevealState? = null
        var view: android.view.View? = null
        rule.setContent { Ot3Theme(motion = rememberOt3Motion(AnimationPreference.FULL, RevealPace.SLOW, style)) {
            val state = rememberRevealState(shown, "evidenceProbe")
            val currentView = LocalView.current
            SideEffect { actual = state; view = currentView }
            Column { Text("${style.name} · Slow · Android AGSL"); Regions(shown) }
        } }
        rule.waitForIdle()
        assertEquals("Hardware accelerated AGSL required for accepted clips", style, actual?.style)
        assertNotNull(actual?.effect)
        rule.waitUntil(5_000) { view?.hasWindowFocus() == true }
        val topPackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
        assertEquals("Evidence must capture the owned app in the foreground", instrumentation.targetContext.packageName, topPackage)
        val captureToken = InstrumentationRegistry.getArguments().getString("revealCaptureToken", "none")
        android.util.Log.i("RevealStyleEvidence", "captureReady token=$captureToken style=$style windowFocus=${view?.hasWindowFocus()} topPackage=$topPackage hardware=${view?.isHardwareAccelerated} shader=${actual?.effect != null} clock=ComposeTestClock step=32ms")
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { shown = false }; frames(32, record = true)
        rule.runOnUiThread { shown = true }; frames(32, record = true)
    }
    private fun frames(count: Int, record: Boolean = false) {
        repeat(count) {
            if (record) rule.mainClock.advanceTimeBy(32) else rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            if (record) java.lang.Thread.sleep(16) // Evidence playback cadence only, never a readiness gate.
        }
    }
    @Composable private fun Regions(shown: Boolean) {
        Column(Modifier.fillMaxSize().background(Color(0xff101629)).padding(12.dp)) {
            Row(Modifier.height(190.dp)) {
                SidebarReveal(shown, Modifier.testTag("reveal_horizontal")) { Pattern("Sidebar · natural width 280 dp") }
            }
            VerticalReveal(shown, Modifier.testTag("reveal_vertical")) {
                Box(Modifier.width(280.dp).height(120.dp)) { Pattern("Vertical · natural height 120 dp") }
            }
        }
    }
    @Composable private fun Pattern(title: String) {
        Column(Modifier.fillMaxSize().background(Color(0xff27365b)).padding(8.dp)) {
            Text(title, color = Color.White)
            repeat(3) { index ->
                Row { repeat(6) { Box(Modifier.padding(3.dp).size(26.dp).background(if ((it + index) % 2 == 0) Color(0xff83cdf0) else Color(0xffa58cdd))) } }
            }
        }
    }
}
