package dev.avillagran.ot3android.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import dev.avillagran.ot3android.ui.settings.SettingsTags
import org.junit.Rule
import org.junit.Test

/** Real navigation and settings screens with the deterministic workspace server. */
class SettingsNavigationDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun settingsAndNestedPagesHaveBackWithoutFooter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(NavTags.tab(TopTab.Settings)).performClick()
            rule.onNodeWithTag(NavTags.BAR).assertDoesNotExist()
            rule.onNodeWithTag(SettingsTags.BACK).assertIsDisplayed()
            listOf("Appearance", "Notifications", "Environments", "Usage", "Archive", "Licenses").forEach { page ->
                rule.onNodeWithTag("settings_$page").performScrollTo().performClick()
                rule.onNodeWithTag(NavTags.BAR).assertDoesNotExist()
                val back = hasContentDescription("Back") or hasText("Back")
                try { rule.waitUntil(10_000) { rule.onAllNodes(back).fetchSemanticsNodes().isNotEmpty() } }
                catch (error: Throwable) { throw AssertionError("Page $page: " + rule.onRoot().printToString(), error) }
                rule.onAllNodes(back).onFirst().performClick()
                rule.onNodeWithTag(SettingsTags.ROOT).assertIsDisplayed()
            }
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitForIdle()
            rule.onNodeWithTag(SettingsTags.BACK).assertIsDisplayed().performClick()
            rule.onNodeWithTag(HomeTags.ROOT).assertIsDisplayed()
            rule.onNodeWithTag(NavTags.BAR).assertIsDisplayed()
        }
    }
}
