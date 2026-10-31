package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.thread.ComposerState
import dev.avillagran.ot3android.core.thread.ThreadView
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-port-xhdpi")
class TranscriptScreenWiringTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val timeline = JSONObject().put("timeline", JSONArray("""[
        {"id":"u","role":"user","text":"first line\nsecond line"},
        {"id":"a","role":"subagents","subagents":[{"id":"child","title":"Child","childThreadId":"other"}]}
    ]"""))

    @Test fun screenQuotesIntoExistingDraftAndOpensChildWithoutSending() {
        var draft by mutableStateOf("Keep this draft")
        var token by mutableStateOf<String?>(null)
        var child = ""
        var sends = 0
        rule.setContent { Ot3Theme {
            ThreadContent(ThreadUiState(ThreadView("chat", loaded = true, timeline = timeline),
                composer = ComposerState(draft = draft, draftToken = token)),
                { draft = it }, { sends++ }, {}, {}, {}, {}, {}, {},
                onDraftChangeTagged = { text, echo -> draft = text; token = echo },
                onOpenThread = { child = it })
        } }
        rule.onNodeWithTag("transcript_quote_u").performClick()
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains("Keep this draft\n\n> first line\n> second line\n\n")
        rule.onNodeWithTag("transcript_agent_child").performClick()
        assertEquals("other", child)
        assertEquals(0, sends)
        rule.onNodeWithTag("transcript_find").assertIsDisplayed()
    }

    @Test fun queueEditingCannotBeOverwrittenByQuote() {
        rule.setContent { Ot3Theme {
            ThreadContent(ThreadUiState(ThreadView("chat", loaded = true, timeline = timeline),
                composer = ComposerState(draft = "Normal draft"),
                queueEdit = """{"runId":"queued","text":"Queued draft"}"""),
                { error("must not edit") }, {}, {}, {}, {}, {}, {}, {})
        } }
        rule.onNodeWithTag("transcript_quote_u").assertDoesNotExist()
        rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains("Queued draft")
    }
}
