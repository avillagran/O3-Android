package dev.avillagran.ot3android.ui.thread

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.avillagran.ot3android.core.composer.*
import dev.avillagran.ot3android.ui.thread.composer.ComposerParity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerRecoveryTest {
    @get:Rule val compose = createComposeRule()
    @Test fun restoredReceiptAlwaysOffersOfflineEditAndConfirmedDiscard() {
        var discarded = false
        val command = dev.avillagran.ot3android.core.thread.PendingCommand("legacy", "env", "chat", "{\"text\":\"Old text\"}", 1, "unknown")
        compose.setContent {
            dev.avillagran.ot3android.ui.theme.Ot3Theme {
                ThreadContent(ThreadUiState(dev.avillagran.ot3android.core.thread.ThreadView("chat", loaded = true),
                    composer = dev.avillagran.ot3android.core.thread.ComposerState(draft = "Old text", unconfirmed = listOf(command))),
                    {}, {}, {}, {}, { discarded = true }, {}, {}, {})
            }
        }
        compose.onAllNodesWithText("Discard").onFirst().performClick()
        assertFalse(discarded)
        compose.onAllNodesWithText("Discard").onLast().performClick()
        assertTrue(discarded)
    }

    @Test fun uncertainOrOfflineDraftCanBeEditedWithoutSending() {
        var edited = ""
        var sent = false
        compose.setContent {
            dev.avillagran.ot3android.ui.theme.Ot3Theme { ComposerParity(ComposerSnapshot(ComposerScope("env", "project", "chat"), text = "Old text", sendBlocked = true),
                emptySet(), onEdit = { _, text, _, _ -> edited = text }, onRequest = { sent = true }) }
        }
        compose.onNodeWithTag(ThreadScreenTags.COMPOSER).assertIsEnabled().performTextReplacement("Recovered text")
        compose.onNodeWithTag(ThreadScreenTags.SEND).assertIsNotEnabled()
        assertEquals("Recovered text", edited)
        assertFalse(sent)
    }
}
