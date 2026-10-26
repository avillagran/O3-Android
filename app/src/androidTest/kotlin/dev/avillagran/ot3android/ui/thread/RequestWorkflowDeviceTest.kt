package dev.avillagran.ot3android.ui.thread

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Actual screens, ViewModel, native projections/builders and durable outbox; synthetic server. */
class RequestWorkflowDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private fun launch(request: String, block: (ActivityScenario<WorkspaceFixtureActivity>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("rich", true).putExtra("request", request)).use { scenario ->
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.request("request-fixture")).fetchSemanticsNodes().isNotEmpty() }
            block(scenario)
        }
    }
    @Test fun explicitApprovalDispatchesExactlyOneNativeDecision() = launch("approval") { scenario ->
        rule.onNodeWithText("Approve", useUnmergedTree = true).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Answered").fetchSemanticsNodes().isNotEmpty() }
        scenario.onActivity {
            val commands = it.fixture.transport.calls.filter { c -> c.fn == "orchestration.dispatchCommand" }
            assertEquals(1, commands.size)
            assertEquals("runtime-request.respond", commands.single().args.getString("type"))
            assertEquals("accept", commands.single().args.getString("decision"))
        }
    }
    @Test fun questionHidesComposerAndSubmitsNativeAnswerValue() = launch("question") { scenario ->
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertDoesNotExist()
        rule.onNodeWithText("Tokyo Night").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Answered").fetchSemanticsNodes().isNotEmpty() }
        scenario.onActivity {
            val command = it.fixture.transport.calls.single { c -> c.fn == "orchestration.dispatchCommand" }.args
            assertEquals("runtime-request.respond", command.getString("type"))
            assertEquals("tokyo-night", command.getJSONObject("answers").getString("theme"))
        }
    }
}
