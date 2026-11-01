package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import dev.avillagran.ot3android.core.composer.*
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.thread.*
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.thread.composer.*
import org.junit.Test
import org.robolectric.annotation.Config

/** Xiaomi pixel dimensions and density, including the split landscape thread pane. */
class PhoneThreadScreenshotTest : ScreenshotTest() {
    @Test @Config(qualifiers = "w359dp-h798dp-port-544dpi")
    fun portrait() = phone(false)
    @Test @Config(qualifiers = "w798dp-h359dp-land-544dpi")
    fun landscape() = phone(true)

    private fun phone(landscape: Boolean) = snapshot(if (landscape) "landscape" else "portrait") {
        CompositionLocalProvider(LocalDensity provides Density(3.4f)) {
            Row(Modifier.fillMaxSize()) {
                if (landscape) Box(Modifier.fillMaxHeight().width(260.dp)) { Text("O3 Code\n\nPinned\n\nPhone layout regression") }
                ThreadContent(ThreadUiState(
                    view = ThreadView("phone", loaded = true, messages = listOf(
                        ThreadMessage("prompt", "user", "/compact", 0, false, "1"),
                        ThreadMessage("reply", "assistant", "Transcript remains the primary surface.", 0, false, "1"))),
                    title = "Phone layout", readingPositionLoaded = true,
                    connection = ConnectionState.Connected(ServerDescriptor("env", "NODE", null, "linux", null))),
                    {}, {}, {}, {}, {}, {}, {}, {}, modifier = Modifier.weight(1f),
                    composerSlot = { controls ->
                        ComposerParity(ComposerSnapshot(ComposerScope("env", "project", "phone")),
                            setOf(ComposerAction.Attach, ComposerAction.Undo, ComposerAction.Redo, ComposerAction.QuickReplies),
                            { _, _, _, _ -> }, {}, toolbar = controls)
                    })
            }
        }
    }
}
