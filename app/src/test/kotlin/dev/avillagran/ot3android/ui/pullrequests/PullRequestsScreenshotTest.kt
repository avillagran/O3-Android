package dev.avillagran.ot3android.ui.pullrequests

import androidx.compose.ui.test.*
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.testing.ScreenshotTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PullRequestsScreenshotTest : ScreenshotTest() {
    private fun data(action: String) = JSONObject(checkNotNull(javaClass.getResource("/pullrequests/$action.view.json")).readText())
    @Test fun listAndReadControls() {
        val result = data("list")
        val state = PullRequestsState(listOf(Environment("node", "NODE", "http://fixture.test", createdAt = 0)), "node", entries = (0 until result.getJSONArray("entries").length()).map { result.getJSONArray("entries").getJSONObject(it) }, result = result)
        var opened = 0; var filter = ""; var more = 0
        snapshot("list") { PullRequestsScreen(state, {}, {}, { filter = it }, {}, {}, {}, { more++ }, { _, e -> opened = e.getInt("number") }) }
        composeRule.onNodeWithTag("pr_entry_42").performClick(); assertEquals(42, opened)
        composeRule.onNodeWithTag("pr_state_merged").performClick(); assertEquals("merged", filter)
        composeRule.onNodeWithTag("pr_list").performScrollToNode(hasTestTag("pr_more")); composeRule.onNodeWithTag("pr_more").performClick(); assertEquals(1, more)
    }
    @Test fun overview() {
        val detail = data("detail")
        snapshot("overview") { PullRequestScreen(42, "example/ot3", PullRequestState(detail = detail, content = detail), {}, {}, {}) }
        composeRule.onNodeWithText("Read-only review").assertExists()
    }
    @Test fun checks() {
        val checks = data("checks")
        snapshot("checks") { PullRequestScreen(42, "example/ot3", PullRequestState(tab = "checks", detail = data("detail"), content = checks), {}, {}, {}) }
        composeRule.onNodeWithText("Android build").assertIsDisplayed()
    }
    @Test fun conversation() {
        val activity = data("activity")
        snapshot("conversation") { PullRequestScreen(42, "example/ot3", PullRequestState(tab = "activity", detail = data("detail"), content = activity), {}, {}, {}) }
        composeRule.onNodeWithText("ui/PullRequestsScreen.kt").assertExists()
    }
    @Test fun offline() { snapshot("offline") { PullRequestsScreen(PullRequestsState(offline = true, environmentId = "node"), {}, {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
    private fun reference() = JSONObject("""{"projectId":"project","repository":"example/ot3","number":42,"host":"github.com"}""")
}
