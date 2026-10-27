package dev.avillagran.ot3android.ui.thread

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.thread.ComposerState
import dev.avillagran.ot3android.core.thread.ThreadView
import dev.avillagran.ot3android.testing.ScreenshotTest
import org.junit.Test

class ComposerEditorScreenshotTest : ScreenshotTest() {
    @Test fun tokyo() = editor("tokyo-night")
    @Test fun latte() = editor("catppuccin-latte")
    private fun editor(theme: String) {
        lateinit var focus: FocusManager
        var draft by mutableStateOf("Expanded composer\nKeep one draft and selection.\nReview the notes before sending.")
        snapshot("compact", themes = listOf(theme)) {
            focus = LocalFocusManager.current
            ThreadContent(ThreadUiState(ThreadView("chat", loaded = true), title = "Draft workspace",
                connection = ConnectionState.Connected(ServerDescriptor("env", "NODE", null, "linux", null)), composer = ComposerState(draft = draft)),
                { draft = it }, {}, {}, {}, {}, {}, {}, {})
        }
        composeRule.onNodeWithTag("composer_expand").performClick()
        composeRule.onNodeWithTag("composer_expanded").assertIsDisplayed()
        // Stable unfocused state; actual-device tests cover focused selection and IME.
        composeRule.runOnIdle { focus.clearFocus() }
        composeRule.waitForIdle()
        captureScreenRoboImage("${roborazziSystemPropertyOutputDirectory()}/ComposerEditorScreenshotTest/expanded.$theme.png")
    }
}
