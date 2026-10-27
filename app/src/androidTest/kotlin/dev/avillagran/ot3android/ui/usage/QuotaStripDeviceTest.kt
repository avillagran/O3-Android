package dev.avillagran.ot3android.ui.usage

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import dev.avillagran.ot3android.ui.thread.ThreadScreenTags
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Actual Home/thread/Usage with a fake transport and the real native quota reducer. */
class QuotaStripDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var keyboardSetting: String? = null
    private fun shell(command: String): String = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        .uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
        }
    @Before fun softwareKeyboard() {
        keyboardSetting = shell("settings get secure show_ime_with_hard_keyboard")
        shell("settings put secure show_ime_with_hard_keyboard 1")
    }
    @After fun restoreKeyboard() {
        keyboardSetting?.let {
            shell(if (it == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $it")
        }
    }
    private fun awaitIme(scenario: ActivityScenario<WorkspaceFixtureActivity>) {
        rule.waitUntil(15_000) {
            var shown = false
            scenario.onActivity {
                shown = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            shown
        }
    }
    private fun toggleQuota(wasShown: Boolean) {
        val text = context.getString(if (wasShown) dev.avillagran.ot3android.R.string.ot3_home_hide_provider_usage else dev.avillagran.ot3android.R.string.ot3_home_show_provider_usage)
        rule.waitUntil(15_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("quota_toggle").assertIsEnabled().assertIsDisplayed().performClick()
    }
    @Before fun resetPreference() = runBlocking { context.appGraph.appearancePreferences.setProviderUsage(false) }
    @After fun restorePreference() = resetPreference()

    @Test fun tokyoToggleNavigationRecreationDraftAndSharedStream() = navigation(true)
    @Test fun latteToggleNavigationRecreationDraftAndSharedStream() = navigation(false)

    @Test fun emptyOfflineReconnectAndAppearancePreference() {
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java).putExtra("request", "quota-empty")).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("nav_tab_Settings").performClick()
            rule.onNodeWithTag("settings_Appearance").performClick()
            rule.onNodeWithTag("appearance_provider_usage").performScrollTo().performClick()
            rule.onNodeWithTag("appearance_provider_usage").assertIsOn()
            scenario.recreate()
            rule.onNodeWithTag("appearance_provider_usage").performScrollTo().assertIsOn()
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithTag(dev.avillagran.ot3android.ui.settings.SettingsTags.BACK).performClick()
            lateinit var fixture: dev.avillagran.ot3android.debug.workspace.WorkspaceFixture
            scenario.onActivity { fixture = it.fixture }
            rule.waitUntil(15_000) { fixture.transport.serverConfigCollectors().value == 1 }
            rule.onNodeWithTag("quota_strip").assertDoesNotExist()
            val supervisor = runBlocking { fixture.connections.supervisor("node")!! }
            runBlocking { supervisor.stopAndJoin() }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("node · Linux · Offline").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("node · Linux · Offline").assertIsDisplayed()
            assertEquals(0, fixture.transport.serverConfigCollectors().value)
            supervisor.start()
            rule.waitUntil(15_000) { fixture.transport.serverConfigCollectors().value == 1 }
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("quota_strip").fetchSemanticsNodes().isEmpty() }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val file = java.io.File(context.getExternalFilesDir(null), "quota-strip/$name.png")
        file.parentFile!!.mkdirs()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }

    private fun navigation(dark: Boolean) {
        val theme = if (dark) "tokyo-night" else "catppuccin-latte"
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java).putExtra("dark", dark)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("quota_strip").assertDoesNotExist()
            capture("home-before.$theme")
            scenario.onActivity { assertEquals(0, it.fixture.transport.serverConfigCollectors().value) }
            rule.onNodeWithTag(HomeTags.OVERFLOW).performClick()
            toggleQuota(false)
            rule.waitUntil(15_000) { rule.onAllNodesWithText("38% used").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("38% used").assertIsDisplayed()
            capture("home-after.$theme")
            scenario.onActivity { assertEquals(1, it.fixture.transport.serverConfigCollectors().value) }
            scenario.recreate()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("38% used").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.COMPOSER).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("38% used").assertIsDisplayed()
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performTextInput("Quota draft retained")
            awaitIme(scenario)
            rule.onNodeWithTag("thread_overflow").performClick()
            toggleQuota(true)
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("quota_strip").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains("Quota draft retained")
            awaitIme(scenario)
            capture("thread-before.$theme")
            rule.onNodeWithTag("thread_overflow").performClick()
            toggleQuota(false)
            rule.waitUntil(15_000) { rule.onAllNodesWithText("38% used").fetchSemanticsNodes().isNotEmpty() }
            awaitIme(scenario)
            capture("thread-after.$theme")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            // Compose idle does not await Android's configuration/recreation. CI also
            // has a software keyboard, unlike the local hardware-keyboard AVD: hide
            // it before tapping the strip, which can otherwise be clipped above the
            // landscape composer while still present in the semantics tree.
            rule.waitUntil(15_000) {
                var landscape = false
                scenario.onActivity {
                    landscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
                landscape && rule.onAllNodesWithTag("quota_group_node · Linux_Codex").fetchSemanticsNodes().size == 2 &&
                    rule.onNodeWithTag("thread_sidebar").isDisplayed()
            }
            // Rotation can request the IME for the recreated composer asynchronously.
            // CI recorded that restore/show arriving after Espresso's earlier hide.
            // Focus the recreated field once and await its IME. Dismiss it with
            // one real Back action so Android owns the IME hide lifecycle instead
            // of clearing AndroidComposeView focus during Compose restoration.
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            rule.waitUntil(15_000) {
                rule.onAllNodes(hasTestTag(ThreadScreenTags.COMPOSER) and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            awaitIme(scenario)
            pressBack()
            // Back must be consumed by the IME, leaving the thread route intact.
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertExists().assertTextContains("Quota draft retained")
            rule.onNodeWithTag("thread_sidebar").assertIsDisplayed()
            rule.waitUntil(15_000) {
                var hidden = false
                scenario.onActivity {
                    hidden = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
                }
                hidden
            }
            rule.waitForIdle()
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertIsDisplayed().assertTextContains("Quota draft retained")
            rule.onNodeWithTag("thread_sidebar").assertIsDisplayed()
            capture("thread-landscape.$theme")
            // Landscape has a live Home sidebar too, with the same shared quota owner.
            scenario.onActivity { assertEquals(1, it.fixture.transport.serverConfigCollectors().value) }
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains("Quota draft retained")
            rule.onAllNodesWithTag("quota_group_node · Linux_Codex").onLast().assertIsDisplayed().performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("usage_tab_limits").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("usage_tab_limits").assertIsSelected()
            scenario.onActivity { assertEquals(1, it.fixture.transport.serverConfigCollectors().value) }
            rule.onNodeWithText("Back").performClick()
            rule.waitForIdle()
            scenario.onActivity {
                assertEquals(1, it.fixture.transport.serverConfigCollectors().value)
                assertEquals(1, it.fixture.transport.calls.count { call -> call.fn == "server.refreshProviders" })
                assertTrue(it.fixture.transport.calls.none { call -> call.fn == "orchestration.dispatchCommand" })
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            rule.waitUntil(15_000) {
                var count = -1
                scenario.onActivity { count = it.fixture.transport.serverConfigCollectors().value }
                count == 0
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            rule.waitUntil(15_000) { rule.onAllNodesWithText("38% used").fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
