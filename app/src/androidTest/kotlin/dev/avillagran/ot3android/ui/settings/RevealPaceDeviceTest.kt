package dev.avillagran.ot3android.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.MainActivity
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.core.data.prefs.RevealPace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual MainActivity theme/preferences and Appearance navigation, no synthetic settings screen. */
class RevealPaceDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun appearancePersistsPaceThroughRecreationAndReturnHome() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.appGraph.appearancePreferences
        val previous = prefs.settings.first()
        try {
            prefs.setRevealPace(RevealPace.NORMAL)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                rule.waitUntil(15_000) { rule.onNodeWithTag("nav_tab_Settings").isDisplayed() }
                rule.onNodeWithTag("nav_tab_Settings").performClick()
                rule.onNodeWithTag("settings_Appearance").performClick()
                rule.onNodeWithTag("appearance_reveal_pace_NORMAL").performScrollTo().assertIsSelected()
                rule.onNodeWithTag("appearance_reveal_pace_SLOW").performScrollTo().performClick()
                rule.waitUntil(15_000) { runBlocking { prefs.settings.first().revealPace == RevealPace.SLOW } }
                capture("appearance-slow")
                scenario.recreate()
                rule.onNodeWithTag("appearance_reveal_pace_SLOW").performScrollTo().assertIsSelected()
                rule.onNodeWithTag("appearance_reveal_pace_FAST").performScrollTo().performClick()
                rule.waitUntil(15_000) { runBlocking { prefs.settings.first().revealPace == RevealPace.FAST } }
                capture("appearance-fast")
                rule.onNodeWithContentDescription("Back").performClick()
                rule.onNodeWithTag(SettingsTags.BACK).performClick()
                rule.waitUntil(15_000) { rule.onNodeWithTag("nav_tab_Settings").isDisplayed() }
                assertEquals(RevealPace.FAST, prefs.settings.first().revealPace)
            }
        } finally { prefs.setRevealPace(previous.revealPace) }
    }
    @Test fun workspaceSidebarUsesSavedSlowAndFastPaces(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.appGraph.appearancePreferences
        val previous = prefs.settings.first()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = context.contentResolver
        val previousScale = android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            prefs.setAnimation(dev.avillagran.ot3android.core.data.prefs.AnimationPreference.FULL)
            prefs.setRevealPace(RevealPace.SLOW)
            ActivityScenario.launch<dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity>(Intent(context,
                dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity::class.java).putExtra("dark", true).putExtra("landscape", true)).use { scenario ->
                rule.waitUntil(15_000) { rule.onNodeWithTag(dev.avillagran.ot3android.ui.home.HomeTags.row("node/chat")).isDisplayed() }
                rule.onNodeWithTag(dev.avillagran.ot3android.ui.home.HomeTags.row("node/chat")).performClick()
                rule.waitUntil(15_000) { rule.onNodeWithTag("thread_sidebar").isDisplayed() }
                fun frames(count: Int) {
                    repeat(count) {
                        rule.mainClock.advanceTimeByFrame()
                        rule.waitForIdle()
                        if (InstrumentationRegistry.getArguments().getString("revealEvidence") == "true") Thread.sleep(16)
                    }
                }
                fun sample(pace: RevealPace): Float {
                    rule.mainClock.autoAdvance = true
                    runBlocking { prefs.setRevealPace(pace) }
                    rule.waitForIdle()
                    rule.mainClock.autoAdvance = false
                    rule.onNodeWithTag("thread_sidebar_toggle").performClick()
                    frames(75)
                    rule.onNodeWithTag("thread_sidebar").assertWidthIsEqualTo(0.dp)
                    rule.onNodeWithTag("thread_sidebar_toggle").performClick()
                    frames(10)
                    val width = rule.onNodeWithTag("thread_sidebar").fetchSemanticsNode().boundsInRoot.width
                    capture("workspace-${pace.name.lowercase()}-160ms")
                    frames(65)
                    return width
                }
                val slow = sample(RevealPace.SLOW)
                val fast = sample(RevealPace.FAST)
                org.junit.Assert.assertTrue("Same 160ms reveal sample: slow=$slow fast=$fast", slow > 0f && fast > slow)
                scenario.onActivity {
                    assertEquals(1, it.fixture.transport.collectors("chat").value)
                    org.junit.Assert.assertTrue(it.fixture.transport.calls.none { call -> call.fn == "orchestration.dispatchCommand" })
                }
            }
        } finally {
            rule.mainClock.autoAdvance = true
            prefs.setRevealPace(previous.revealPace)
            prefs.setAnimation(previous.animation)
            android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("revealEvidence") != "true") return
        rule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "reveal-pace/$name.png").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
