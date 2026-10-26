package dev.avillagran.ot3android.ui.thread

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ThreadDiffDialogTest {
    @get:Rule val rule = createComposeRule()

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
