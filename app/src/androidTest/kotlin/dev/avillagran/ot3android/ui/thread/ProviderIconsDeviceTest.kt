package dev.avillagran.ot3android.ui.thread

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import org.junit.Rule
import org.junit.Test

/** Same model slug/name across five native-catalog provider identities, both palettes. */
class ProviderIconsDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun providerIdentityIconsRenderInRealPickerInLightAndDark() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(false, true).forEach { dark ->
            ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
                .putExtra("request", "providers").putExtra("dark", dark)).use {
                rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
                rule.waitUntil(15_000) { rule.onAllNodesWithTag("composer_models").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("composer_models").performClick()
                rule.waitUntil(15_000) { rule.onAllNodesWithTag("model_provider_fixture-provider").fetchSemanticsNodes().isNotEmpty() }
                listOf("fixture-provider", "provider-1", "provider-2", "provider-3", "provider-4").forEach { id ->
                    rule.onNodeWithTag("model_provider_$id").performScrollTo().performClick()
                    rule.onNode(hasTestTag("provider_icon_$id") and hasAnyAncestor(hasTestTag("model_provider_$id")), useUnmergedTree = true).assertIsDisplayed()
                    rule.onNodeWithText("Mismo modelo").assertIsDisplayed()
                }
            }
        }
    }
}
