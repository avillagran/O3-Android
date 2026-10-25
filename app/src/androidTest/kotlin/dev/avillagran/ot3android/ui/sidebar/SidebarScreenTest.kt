package dev.avillagran.ot3android.ui.sidebar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.shell.ShellReducer
import dev.avillagran.ot3android.core.shell.SidebarModel
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SidebarScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun thread(id: String, project: String, vararg fields: Pair<String, Any>) =
        JSONObject().put("id", id).put("projectId", project).put("title", "Thread $id")
            .put("updatedAt", "2026-10-24T10:00:00.000Z").apply { fields.forEach { (k, v) -> put(k, v) } }

    private val shell = ShellReducer.parse(
        JSONObject().put("snapshotSequence", 3)
            .put(
                "projects",
                JSONArray().put(JSONObject().put("id", "p1").put("title", "Alpha"))
                    .put(JSONObject().put("id", "p2").put("title", "Beta")),
            )
            .put(
                "threads",
                JSONArray()
                    .put(thread("a1", "p1", "activeRunId" to "run-1"))
                    .put(thread("b1", "p2", "pendingRuntimeRequest" to JSONObject().put("kind", "approval")))
                    .put(thread("s1", "p1", "settledAt" to "2026-10-23T00:00:00.000Z"))
                    .put(thread("sub", "p1", "lineage" to JSONObject().put("relationshipToParent", "subagent"))),
            ),
    )

    @Test
    fun rendersSectionsGroupsAndStatuses() {
        val opened = mutableListOf<String>()
        var expanded by mutableStateOf(false)
        composeRule.setContent {
            Ot3Theme {
                SidebarContent(
                    state = SidebarUiState(
                        label = "Workstation",
                        connection = ConnectionState.Connected(ServerDescriptor("env-1", "Workstation", "0.1.3", "linux", 2)),
                        items = SidebarModel.build(shell).toItems(expanded),
                    ),
                    onOpenThread = { opened += it },
                    onToggleSettled = { expanded = !expanded },
                    onRefresh = {},
                    onRetry = {},
                    onEditEnvironment = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(SidebarScreenTags.ACTIVE_HEADER).assertIsDisplayed()
        composeRule.onNodeWithTag(SidebarScreenTags.project("p1")).assertTextContains("Alpha")
        composeRule.onNodeWithTag(SidebarScreenTags.project("p2")).assertTextContains("Beta")
        composeRule.onNodeWithTag(SidebarScreenTags.status("a1")).assertTextContains("Working")
        composeRule.onNodeWithTag(SidebarScreenTags.status("b1")).assertTextContains("Needs you")
        composeRule.onNodeWithTag(SidebarScreenTags.thread("sub")).assertDoesNotExist()
        composeRule.onNodeWithTag(SidebarScreenTags.SETTLED_HEADER).assertTextContains("Settled (1)")
        composeRule.onNodeWithTag(SidebarScreenTags.thread("s1")).assertDoesNotExist()

        composeRule.onNodeWithTag(SidebarScreenTags.SETTLED_HEADER).performClick()
        composeRule.onNodeWithTag(SidebarScreenTags.LIST).performScrollToNode(hasTestTag(SidebarScreenTags.thread("s1")))
        composeRule.onNodeWithTag(SidebarScreenTags.thread("s1")).assertIsDisplayed()

        composeRule.onNodeWithTag(SidebarScreenTags.thread("a1")).performClick()
        assertEquals(listOf("a1"), opened)
    }

    @Test
    fun unauthorizedOffersRetryAndTokenEdit() {
        composeRule.setContent {
            Ot3Theme {
                SidebarContent(
                    state = SidebarUiState(label = "Workstation", connection = ConnectionState.Unauthorized),
                    onOpenThread = {}, onToggleSettled = {}, onRefresh = {}, onRetry = {}, onEditEnvironment = {}, onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(SidebarScreenTags.RETRY).assertIsDisplayed()
        composeRule.onNodeWithTag(SidebarScreenTags.EDIT_TOKEN).assertIsDisplayed()
    }
}
