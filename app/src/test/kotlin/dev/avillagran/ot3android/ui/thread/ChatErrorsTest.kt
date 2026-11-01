package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.t3.T3Exception
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ChatErrorsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shown(notice: ThreadNotice.Failed): String {
        var text = ""
        rule.setContent { text = chatErrorText(notice) }
        rule.waitForIdle()
        return text
    }

    @Test fun hostExceptionKeepsItsCode() {
        assertEquals("write_failed", chatFailure(T3Exception("write_failed")).code)
    }

    @Test fun stateCheckKeepsItsCode() {
        assertEquals(ThreadNotice.Failed("connection_changed"), chatFailure(IllegalStateException("connection_changed")))
    }

    @Test fun sentenceMessageStaysAsTheReason() {
        val failure = chatFailure(JSONException("No value for thread"))
        assertEquals(CHAT_ACTION_FAILED, failure.code)
        assertEquals("No value for thread", failure.reason)
    }

    @Test fun exceptionWithoutMessageNamesItsType() {
        assertEquals("ClassCastException", chatFailure(ClassCastException()).reason)
    }

    @Test fun longMessageIsBounded() {
        assertEquals(200, chatFailure(IllegalStateException("not a code ".repeat(50))).reason?.length)
    }

    @Test fun everyActionCodeHasItsOwnMessage() {
        listOf("connection_changed", "thread_unavailable", "run_unavailable", "plan_unavailable", "plan_scope_changed",
            "new_thread_recipe_unavailable", "project_unavailable", "composer_changed", "thread_is_working",
            "attachment_authority_unavailable", "attachment_authority_changed", "attachment_storage_unavailable",
        ).forEach { assertNotNull(it, chatErrorMessage(it)) }
    }

    @Test fun actionErrorIsNotShownAsAConnectionFailure() {
        assertEquals("That response is no longer available.", shown(ThreadNotice.Failed("run_unavailable")))
    }

    @Test fun unknownCodeNamesTheFailedActionNotTheConnection() {
        assertEquals("Could not complete that action (history_unavailable).", shown(ThreadNotice.Failed("history_unavailable")))
    }

    @Test fun reasonReplacesTheGenericCode() {
        assertEquals("Could not complete that action (route unavailable).", shown(ThreadNotice.Failed(CHAT_ACTION_FAILED, "route unavailable")))
    }

    @Test fun connectionCodesKeepTheirConnectionMessage() {
        assertTrue(shown(ThreadNotice.Failed("unreachable")).startsWith("Could not reach the server."))
    }
}
