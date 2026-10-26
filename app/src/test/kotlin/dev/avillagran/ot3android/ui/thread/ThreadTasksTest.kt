package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ThreadTasksTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val tasks = JSONObject("""{"planId":"todo","runId":"active","current":"Verify native reader","completed":1,"total":2,"items":[{"id":"done","text":"Build","status":"completed","durationMs":3000},{"id":"live","text":"Verify native reader","status":"running"}]}""")

    @Test fun projectedCountsAndDurationsAreVisibleAndExpandedStateRestores() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Ot3Theme { ThreadTasks(tasks) } }
        rule.onNodeWithText("1/2").assertIsDisplayed()
        rule.onNodeWithTag("thread_tasks_list").assertDoesNotExist()
        rule.onNodeWithTag("thread_tasks_toggle").performClick()
        rule.onNodeWithText("Build").assertIsDisplayed()
        rule.onNodeWithText("3s").assertIsDisplayed()
        rule.onNodeWithText("now").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("thread_tasks_list").assertIsDisplayed()
        rule.onNodeWithTag("thread_tasks_toggle").performClick()
        rule.onNodeWithTag("thread_tasks_list").assertDoesNotExist()
    }

    @Test fun noNativeTaskProjectionReservesNoStrip() {
        rule.setContent { Ot3Theme { ThreadTasks(null) } }
        rule.onNodeWithTag("thread_tasks").assertDoesNotExist()
    }
}
