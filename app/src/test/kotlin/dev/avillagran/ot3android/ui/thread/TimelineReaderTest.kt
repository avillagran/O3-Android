package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.testing.assumeHostJni
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-port-xhdpi")
class TimelineReaderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun rustMarkdownHasHeadingsListsLinksEmphasisAndCopyableCode() {
        assumeHostJni()
        val view = runBlocking { DomainEngine.shared.domain("timeline.view", JSONObject().put("projection", JSONObject("""{
          "thread":{"id":"chat"},"messages":[{"id":"answer","role":"assistant","createdAt":1,
          "text":"# Native reader\n\n**Bold** and *emphasis* with [reference](https://example.org)\n\n- First\n- Second\n\n```kotlin\nval answer = 42\n```"}]
        }"""))) as JSONObject }
        check(view.getJSONArray("timeline").getJSONObject(0).has("blocks"))
        rule.setContent { Ot3Theme { TimelineReader(view, emptyList()) } }
        rule.onNodeWithText("Native reader").assertIsDisplayed()
        rule.onNodeWithText("First").assertIsDisplayed()
        rule.onNodeWithText("val answer = 42\n", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Copy").onFirst().performClick()
    }

    @Test fun fullRowsAreNotDroppedAndActivityAndChangesExpand() {
        val rows = JSONArray("""[
          {"id":"changes","role":"changes","changes":{"additions":4,"deletions":1,"files":[{"path":"Reader.kt","kind":"modified","additions":4,"deletions":1}]}},
          {"id":"work","role":"activity","activity":{"category":"think","status":"completed","title":""}},
          {"id":"future","role":"future-role"}
        ]""")
        rule.setContent { Ot3Theme { TimelineReader(JSONObject().put("timeline", rows), emptyList()) } }
        rule.onNodeWithTag("thread_unknown_future").assertIsDisplayed()
        rule.onNodeWithTag("thread_fold_work").performClick()
        rule.onAllNodesWithText("think · completed", substring = true).assertCountEquals(2)
        rule.onNodeWithTag("thread_fold_changes").performClick()
        rule.onNodeWithText("Reader.kt", substring = true).assertIsDisplayed()
    }
}
