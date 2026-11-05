package dev.avillagran.ot3android.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.home.layouts.HostStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class HostIdentityScreenshotTest : ScreenshotTest() {
    @Test fun chatHeaderUsesSavedHostIdentityInsteadOfComposerServerId() = snapshot("chat-header", beforeCapture = {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("NODE").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color(0xff8fc46a), layouts.single().layoutInput.style.color)
    }) {
        CompositionLocalProvider(LocalHostStyles provides mapOf("saved-node" to HostStyle("linux", "#8fc46a", true))) {
            dev.avillagran.ot3android.ui.thread.ThreadContent(
                dev.avillagran.ot3android.ui.thread.ThreadUiState(
                    dev.avillagran.ot3android.core.thread.ThreadView("chat", loaded = true),
                    hostEnvironmentId = "saved-node", hostLabel = "NODE", title = "Host identity",
                    composerScope = dev.avillagran.ot3android.core.composer.ComposerScope("canonical-server-uuid", "project", "chat")),
                {}, {}, {}, {}, {}, {}, {}, {})
        }
    }

    @Test fun sharedIdentitySurvivesThemeChanges() = snapshot("shared", beforeCapture = {
        listOf("NODE" to Color(0xff8fc46a), "mac-project" to Color(0xffe06c75)).forEach { (text, expected) ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(expected, layouts.single().layoutInput.style.color)
        }
    }) {
        CompositionLocalProvider(LocalHostStyles provides mapOf(
            "node" to HostStyle("linux", "#8fc46a", true),
            "mac" to HostStyle("mac-mini", "#e06c75", true))) {
            Column {
                HostBadge("NODE", "node")
                HostProjectTitle("mac-project", "mac")
            }
        }
    }
}
