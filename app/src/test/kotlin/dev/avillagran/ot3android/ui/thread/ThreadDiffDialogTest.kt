package dev.avillagran.ot3android.ui.thread

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ThreadDiffDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun nativeHunkHeadersFoldOnlyTheirFollowingRowsAndSurviveRestore() {
        val view = JSONObject(checkNotNull(javaClass.getResource("/timeline/diff.view.json")).readText())
        val rows = view.getJSONArray("files").getJSONObject(0).getJSONArray("rows")
        // Delimitation is the native kind, never text parsing in the UI.
        rows.put(JSONObject().put("kind", "hunk").put("text", "Second native header"))
        rows.put(JSONObject().put("kind", "addition").put("text", "+secondHunk()"))
        var reads = 0
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Ot3Theme {
            ThreadDiffDialog(JSONObject("""{"fromTurnCount":2,"toTurnCount":3}"""), { _, _ -> reads++; view }, {})
        } }
        val first = rule.onNodeWithTag("thread_diff_hunk_0_2")
        first.assertHeightIsAtLeast(48.dp)
        first.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
        first.performClick()
        first.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
        rule.onNodeWithText("+renderNativeBlocks()").assertDoesNotExist()
        rule.onNodeWithText("--- a/reader.kt").assertExists()
        rule.onNodeWithText("+secondHunk()").assertIsDisplayed()
        rule.onNodeWithTag("thread_diff_wrap").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("+renderNativeBlocks()").assertDoesNotExist()
        rule.onNodeWithText("+secondHunk()").assertIsDisplayed()
        first.performSemanticsAction(SemanticsActions.Expand) { it() }
        rule.onNodeWithText("+renderNativeBlocks()").assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, reads) }
    }

    @Test fun fileWhitespaceAndChangedDiffResetFoldsWithoutMutatingNativeRows() {
        val view = JSONObject(checkNotNull(javaClass.getResource("/timeline/diff.view.json")).readText())
        val original = view.toString()
        var reads = 0
        val summary = androidx.compose.runtime.mutableStateOf(JSONObject("""{"fromTurnCount":2,"toTurnCount":3}"""))
        rule.setContent { Ot3Theme { ThreadDiffDialog(summary.value, { _, _ -> reads++; view }, {}) } }
        fun fold() { rule.onNodeWithTag("thread_diff_hunk_0_2").performClick() }
        fold()
        rule.onNodeWithTag("thread_diff_next").performClick()
        rule.onNodeWithTag("thread_diff_previous").performClick()
        rule.onNodeWithText("+renderNativeBlocks()").assertIsDisplayed()
        fold()
        rule.onNodeWithTag("thread_diff_whitespace").performClick()
        rule.onNodeWithText("+renderNativeBlocks()").assertIsDisplayed()
        rule.onNodeWithTag("thread_diff_whitespace").performClick() // A → B → A must not resurrect A's folds.
        rule.onNodeWithText("+renderNativeBlocks()").assertIsDisplayed()
        fold()
        rule.runOnIdle { summary.value = JSONObject("""{"fromTurnCount":3,"toTurnCount":4}""") }
        rule.onNodeWithText("+renderNativeBlocks()").assertIsDisplayed()
        rule.runOnIdle { assertEquals(original, view.toString()); assertEquals(4, reads) }
    }

    @Test fun noWrapScrollsTheWholePatchWithoutRefetchAndSurvivesRecreation() {
        val view = JSONObject(checkNotNull(javaClass.getResource("/timeline/diff.view.json")).readText())
        view.getJSONArray("files").getJSONObject(0).getJSONArray("rows").getJSONObject(0)
            .put("text", "long selectable patch text ".repeat(12))
        var reads = 0
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            Ot3Theme { ThreadDiffDialog(JSONObject("""{"fromTurnCount":2,"toTurnCount":3}"""), { _, _ -> reads++; view }, {}) }
        }
        rule.onNodeWithTag("thread_diff_horizontal").assertDoesNotExist()
        rule.onNodeWithTag("thread_diff_wrap").performClick()
        val patch = rule.onNodeWithTag("thread_diff_horizontal")
        assertTrue(patch.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0)
        patch.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue(patch.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0)
        rule.runOnIdle { assertEquals(1, reads) }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("thread_diff_wrap").assertTextContains("Wrap")
        rule.onNodeWithTag("thread_diff_horizontal").assertExists()
        rule.onNodeWithTag("thread_diff_wrap").performClick()
        rule.onNodeWithTag("thread_diff_horizontal").assertDoesNotExist()
        rule.runOnIdle { assertEquals(2, reads) }
    }

    @Test fun fileNavigationIsLocalAndSelectionSurvivesRefreshAndRecreation() {
        val view = JSONObject(checkNotNull(javaClass.getResource("/timeline/diff.view.json")).readText())
        var reads = 0
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            Ot3Theme {
                ThreadDiffDialog(JSONObject("""{"fromTurnCount":2,"toTurnCount":3}"""), { _, _ -> reads++; view }, {})
            }
        }
        rule.onNodeWithTag("thread_diff_previous").assertIsNotEnabled()
        rule.onNodeWithTag("thread_diff_next").performClick()
        rule.onNodeWithText("Binary file — metadata only").assertIsDisplayed()
        rule.onNodeWithText("+renderNativeBlocks()").assertDoesNotExist()
        rule.onNodeWithTag("thread_diff_file_picker").performClick()
        rule.onNodeWithTag("thread_diff_file_2").performClick()
        rule.onNodeWithText("Patch unavailable for this file.").assertIsDisplayed()
        rule.onNodeWithTag("thread_diff_next").assertIsNotEnabled()
        rule.onNodeWithTag("thread_diff_previous").performClick()
        rule.runOnIdle { assertEquals("Selection must not fetch again", 1, reads) }
        rule.onNodeWithTag("thread_diff_whitespace").performClick()
        rule.onNodeWithTag("thread_diff_file_picker").assertTextContains("icon.png")
        rule.runOnIdle { assertEquals(2, reads) }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("thread_diff_file_picker").assertTextContains("icon.png")
        rule.onNodeWithText("Binary file — metadata only").assertIsDisplayed()
    }

    @Test fun failedReadCanRetryAndWhitespaceRefetches() {
        val view = JSONObject(checkNotNull(javaClass.getResource("/timeline/diff.view.json")).readText())
        val filters = mutableListOf<Boolean>()
        var closed = false
        rule.setContent {
            Ot3Theme {
                ThreadDiffDialog(JSONObject("""{"fromTurnCount":2,"toTurnCount":3}"""), { _, ignore ->
                    filters += ignore
                    if (filters.size == 1) error("offline")
                    view
                }, { closed = true })
            }
        }
        rule.onNodeWithTag("thread_diff_retry").performClick()
        rule.onNodeWithText("+renderNativeBlocks()").assertIsDisplayed()
        rule.onNodeWithTag("thread_diff_whitespace").performClick()
        rule.waitForIdle()
        assertEquals(listOf(true, true, false), filters)
        rule.onNodeWithTag("thread_diff_close").performClick()
        assertTrue(closed)
    }
}
