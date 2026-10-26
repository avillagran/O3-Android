package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ApprovalCardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun rows(live: Boolean = true) = JSONArray("""[{"requestId":"r","requestKind":"command","responseCapability":"${if(live) "live" else "not_resumable"}","detail":"cat README.md","options":[{"decision":"accept","label":"Approve"},{"decision":"decline","label":"Decline"},{"decision":"acceptForSession","label":"Allow this session","warning":"Applies to later requests"}]}]""")

    @Test fun choicesRemainVisibleAndRequireAnExplicitTap() {
        val responses = mutableListOf<Pair<String, String>>()
        rule.setContent { Ot3Theme { ApprovalCard(rows(), true, null, emptySet()) { id, decision -> responses += id to decision } } }
        rule.onNodeWithText("cat README.md").assertIsDisplayed()
        rule.runOnIdle { assertEquals(emptyList<Pair<String, String>>(), responses) }
        rule.onNodeWithContentDescription("More approval options").performClick()
        rule.onNodeWithText("⚠ Allow this session").performClick()
        rule.runOnIdle { assertEquals(listOf("r" to "acceptForSession"), responses) }
    }

    @Test fun goneProviderNeverOffersAnActionableApproval() {
        rule.setContent { Ot3Theme { ApprovalCard(rows(false), true, null, emptySet()) { _, _ -> error("not resumable") } } }
        rule.onNodeWithText("Approve").assertIsNotEnabled()
        rule.onNodeWithText("Decline").assertIsNotEnabled()
        rule.onNodeWithText("Provider process is gone — interrupt or restart the run to respond.").assertIsDisplayed()
    }

    @Test fun acceptedResultDisablesTheCardWhileWaitingForTheStream() {
        rule.setContent { Ot3Theme { ApprovalCard(rows(), true, null, setOf("r")) { _, _ -> error("duplicate response") } } }
        rule.onNodeWithText("Answered").assertIsDisplayed()
        rule.onNodeWithText("Approve").assertIsNotEnabled()
    }
}
