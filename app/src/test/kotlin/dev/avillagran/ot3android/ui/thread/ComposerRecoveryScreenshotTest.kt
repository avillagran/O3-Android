package dev.avillagran.ot3android.ui.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import dev.avillagran.ot3android.core.composer.*
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.thread.composer.*
import org.junit.Test
import org.robolectric.annotation.Config

/** Xiaomi viewport: exact physical pixels with the production 3.4 density. */
class ComposerRecoveryScreenshotTest : ScreenshotTest() {
    @Test @Config(qualifiers = "w1220dp-h2712dp-port-mdpi")
    fun portrait() = recovery()
    @Test @Config(qualifiers = "w2712dp-h1220dp-land-mdpi")
    fun landscape() = recovery()
    private fun recovery() = snapshot("recovery") {
        CompositionLocalProvider(LocalDensity provides Density(3.4f)) {
            ComposerParity(ComposerSnapshot(ComposerScope("env", "project", "thread"), text = "Old message preserved for editing",
                sendBlocked = true), setOf(ComposerAction.Attach, ComposerAction.History, ComposerAction.ProjectDrafts),
                onEdit = { _, _, _, _ -> }, onRequest = {})
        }
    }
}
