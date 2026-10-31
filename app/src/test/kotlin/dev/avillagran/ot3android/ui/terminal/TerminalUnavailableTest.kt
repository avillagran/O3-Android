package dev.avillagran.ot3android.ui.terminal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TerminalUnavailableTest {
    @get:Rule val rule = createComposeRule()
    @Test fun readOnlyShowsExplicitLimitationWithoutInputOrExecution() {
        var closed = 0
        rule.setContent { Ot3Theme { TerminalUnavailable("host-a", "thread-a", false) { closed++ } } }
        rule.onNodeWithText("host-a").assertExists()
        rule.onNodeWithText("thread-a").assertExists()
        rule.onNodeWithText("Read-only token: this host requires terminal:operate even to attach. A read-only stream is unavailable.").assertExists()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithTag("terminal_close").performClick()
        rule.runOnIdle { assertEquals(1, closed) }
    }
    @Test fun disconnectedDoesNotOfferAReconnectThatCouldStartAShell() {
        rule.setContent { Ot3Theme { TerminalUnavailable("host-b", "thread-b", null) {} } }
        rule.onNodeWithText("Disconnected. Reopen after the host reconnects.").assertExists()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithTag("terminal_close").assertIsEnabled()
    }
}
