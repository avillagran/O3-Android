package dev.avillagran.ot3android.ui.terminal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TerminalUnavailableDeviceTest {
    @get:Rule val rule = createComposeRule()
    @Test fun readOnlyCannotTypeOrExecuteAndCanClose() {
        var closed = 0
        rule.setContent { Ot3Theme { TerminalUnavailable("remote-host", "remote-thread", false) { closed++ } } }
        rule.onNodeWithText("remote-host").assertIsDisplayed()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithTag("terminal_close").performClick()
        rule.runOnIdle { assertEquals(1, closed) }
    }
}
