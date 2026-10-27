package dev.avillagran.ot3android.ui.newchat

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-port-xhdpi")
class NewChatProjectPickerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val node = NewChatProjectOption("node-id", "NODE", "p1", "Shared project", "/node/project", true)
    private val mac = node.copy(environmentId = "mac-id", environmentLabel = "MBP-M2", workspaceRoot = "/mac/project")
    @Test fun identicalProjectNamesStayScopedToTheirHostAndSelectTheCorrectProject() {
        var selected: NewChatProjectOption? = null
        rule.setContent { Ot3Theme { NewChatProjectPicker(listOf(node, mac), false, { selected = it }, {}, {}) } }
        rule.onAllNodesWithText("Shared project").assertCountEquals(2)
        rule.onNodeWithText("NODE").assertIsDisplayed()
        rule.onNodeWithText("MBP-M2").assertIsDisplayed()
        rule.onNodeWithTag("new_chat_project_mac-id/p1").performClick()
        assertEquals(mac, selected)
    }
    @Test fun offlineAndReadOnlyProjectsRemainVisibleButCannotLaunch() {
        var selected = false
        rule.setContent { Ot3Theme { NewChatProjectPicker(listOf(node.copy(connected = false), mac.copy(writable = false)), false,
            { selected = true }, {}, {}) } }
        rule.onNodeWithTag("new_chat_project_node-id/p1").assertIsNotEnabled()
        rule.onNodeWithTag("new_chat_project_mac-id/p1").assertIsNotEnabled()
        assertFalse(selected)
    }
    @Test fun searchFindsAProjectByItsEnvironmentAndRetryIsAvailableForNoMatches() {
        var retries = 0
        rule.setContent { Ot3Theme { NewChatProjectPicker(listOf(node, mac), false, {}, {}, { retries++ }) } }
        rule.onNodeWithTag("new_chat_project_search").performTextInput("MBP")
        rule.onNodeWithTag("new_chat_project_mac-id/p1").assertIsDisplayed()
        rule.onNodeWithTag("new_chat_project_node-id/p1").assertDoesNotExist()
        rule.onNodeWithTag("new_chat_project_search").performTextReplacement("missing")
        rule.onNodeWithText("No projects found").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }
    @Test fun draftNeedsAnAvailableModelAndWritePermissionBeforeSending() {
        var submitted = 0
        val catalog = """{"models":[{"instanceId":"provider","model":"model","available":true}],"providers":[]}"""
        val selection = """{"instanceId":"provider","model":"model"}"""
        val state = mutableStateOf(NewChatDraftState(loading = false, writable = false,
            projectTitle = "Selected project", catalog = catalog, selection = selection, text = "Task"))
        rule.setContent { Ot3Theme { NewChatDraftScreen(state.value, {}, {}, {}, {}, { _, _ -> }, {}, {}, { submitted++ }) } }
        rule.onNodeWithTag("new_chat_send").assertIsNotEnabled()
        rule.runOnIdle { state.value = state.value.copy(writable = true, text = "") }
        rule.onNodeWithTag("new_chat_send").assertIsNotEnabled()
        rule.runOnIdle { state.value = state.value.copy(text = "Task", selection = null) }
        rule.onNodeWithTag("new_chat_send").assertIsNotEnabled()
        rule.runOnIdle { state.value = state.value.copy(selection = selection) }
        rule.onNodeWithTag("new_chat_send").assertIsEnabled().performClick()
        assertEquals(1, submitted)
    }
}
