package dev.avillagran.ot3android.ui.git

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.thread.ThreadReviewDialog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w891dp-h900dp-land-xhdpi")
class GitSurfacesTest {
    @get:Rule val rule = createComposeRule()
    private fun snapshot() = JSONObject("""{"scope":{"environment":"host","thread":"t","projectId":"p","cwd":"/repo","requestId":"r"},"revision":"v1","actions":{"push":true,"commit":true},"scripts":[],"status":{"isRepo":true,"refName":"main","isDefaultRef":true,"hasWorkingTreeChanges":true,"workingTree":{"files":[{"path":"src/test.kt","insertions":1,"deletions":0}]}}}""")

    @Test fun dirtyDefaultBranchNeedsBothAcknowledgementsAndEmitsOnce() {
        val intents = mutableListOf<JSONObject>()
        rule.setContent { Ot3Theme { GitControlsSurface(snapshot(), true, true, true, true, null, false, null, {}, { intents += it }, null, {}, {}) } }
        rule.onNodeWithText("Push").performScrollTo().performClick()
        rule.onNodeWithTag("git_confirm").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("I confirm this operates on the default branch.").performScrollTo().performClick()
        rule.onNodeWithTag("git_confirm").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Uncommitted changes").performScrollTo().performClick()
        rule.onNodeWithTag("git_confirm").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithTag("git_confirm").assertDoesNotExist()
        assertEquals(1, intents.size)
        assertTrue(intents.single().getBoolean("defaultBranchConfirmed"))
        assertEquals("push", intents.single().getString("operation"))
    }
    @Test fun missingStreamAndDisconnectedNeverOfferWritableCommit() {
        var connected by mutableStateOf(true)
        rule.setContent { Ot3Theme { GitControlsSurface(snapshot(), connected, true, true, false, null, false, null, {}, {}, null, {}, {}) } }
        rule.onNodeWithText("Commit").assertIsNotEnabled()
        rule.runOnIdle { connected = false }
        rule.onNodeWithText("Commit").assertIsNotEnabled()
    }
    @Test fun unknownOperationBlocksAllNewActionsAcrossRestoration() {
        val restoration = StateRestorationTester(rule)
        val record = JSONObject("""{"id":"a","state":"unknown"}""")
        restoration.setContent { Ot3Theme { GitControlsSurface(snapshot(), true, true, true, true, record, false, null, {}, {}, null, {}, {}) } }
        rule.onNodeWithText("Push").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Commit").assertIsNotEnabled()
    }
    @Test fun checkpointFilesNeedWorktreeAndAcknowledgement() {
        var calls = 0
        val preview = JSONObject("""{"requestId":"r","gate":{"allowed":true},"message":{"text":"prompt","attachments":[]}}""")
        rule.setContent { Ot3Theme { CheckpointRollbackSurface(preview, null, true, true, true, { files, acknowledged -> assertTrue(files); assertTrue(acknowledged); calls++ }, {}, {}, {}) } }
        rule.onNodeWithContentDescription("Revert files too").performClick()
        rule.onNodeWithTag("checkpoint_confirm").assertIsNotEnabled()
        rule.onNodeWithContentDescription("This operation can change files or remote state.").performClick()
        rule.onNodeWithTag("checkpoint_confirm").assertIsEnabled().performClick()
        rule.onNodeWithTag("checkpoint_confirm").assertDoesNotExist()
        assertEquals(1, calls)
    }
    @Test fun uncertainCheckpointOnlyRefreshesWithoutRollback() {
        var refreshed = 0; var writes = 0
        rule.setContent { Ot3Theme { CheckpointRollbackSurface(null, JSONObject("""{"id":"a","state":"unknown"}"""), true, true, true, { _, _ -> writes++ }, { refreshed++ }, {}, {}) } }
        rule.onNodeWithTag("checkpoint_confirm").assertDoesNotExist()
        rule.onNodeWithText("Refresh").performClick()
        assertEquals(1, refreshed); assertEquals(0, writes)
    }
    @Test fun reviewSettingsTreeContextAndHunkNavigationAreReachable() {
        val view = JSONObject(checkNotNull(javaClass.getResource("/timeline/diff.view.json")).readText())
        var setting = DiffReviewSettings()
        rule.setContent { Ot3Theme { ThreadReviewDialog(JSONObject(), { _, _ -> view }, {}, onSettingsChange = { setting = it }) } }
        rule.onNodeWithText("Hide context").performClick()
        assertFalse(setting.showContext)
        rule.onNodeWithText("Next hunk").assertIsDisplayed().performClick()
        rule.onNodeWithText("Files").performClick()
        rule.onNodeWithTag("thread_diff_tree").assertIsDisplayed()
    }
}
