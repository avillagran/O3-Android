package dev.avillagran.ot3android.ui.sky

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avillagran.ot3android.core.home.HomeContent
import dev.avillagran.ot3android.core.home.HomeUiState
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.home.HomeScreen
import dev.avillagran.ot3android.ui.navigation.ThreadWorkspace
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Test

/**
 * JVM stills of the two skies (UI_SPEC 3.5) in the reference themes: the panel behind Home's
 * list, the panel behind a chat and the whole sidebar, and the phone's drawer. Robolectric
 * native graphics, not a device: see `docs/ui/compare/sky-chat-sidebar/` for what each
 * capture is.
 */
class SkySurfaceScreenshotTest : ScreenshotTest() {
    @Test fun home() = snapshot("home-panel") {
        HomeScreen(HomeUiState(content = HomeContent.Empty), true, {})
    }

    @Test fun workspace() = snapshot("workspace-panel") {
        Landscape {
            ThreadWorkspace(sidebar = { SidebarFixture() }) { ChatFixture() }
        }
    }

    @Test fun drawer() {
        // The drawer opens once; the theme change re-renders the same composition with it open.
        var opened = false
        snapshot("drawer-panel", beforeCapture = {
            if (!opened) {
                opened = true
                composeRule.onNodeWithTag("thread_sidebar_toggle").performClick()
            }
        }) {
            Portrait { ThreadWorkspace(sidebar = { SidebarFixture() }) { ChatFixture() } }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Landscape(content: @androidx.compose.runtime.Composable () -> Unit) {
        val config = Configuration(LocalConfiguration.current).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            screenWidthDp = 891
            screenHeightDp = 411
            smallestScreenWidthDp = 411
        }
        CompositionLocalProvider(LocalConfiguration provides config) { content() }
    }

    @androidx.compose.runtime.Composable
    private fun Portrait(content: @androidx.compose.runtime.Composable () -> Unit) {
        val config = Configuration(LocalConfiguration.current).apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = 411
            screenHeightDp = 891
            smallestScreenWidthDp = 411
        }
        CompositionLocalProvider(LocalConfiguration provides config) { content() }
    }

    /** Home's own chrome at the top of the sidebar, then a few rows of plain text. */
    @androidx.compose.runtime.Composable
    private fun SidebarFixture() {
        val colors = Ot3Theme.colors
        Column(Modifier.fillMaxSize().testTag("fixture_sidebar")) {
            ThreadWorkspaceHeader(colors.foreground)
            Spacer(Modifier.height(12.dp))
            repeat(6) { index ->
                Text(
                    listOf("node", "mobile", "Thread ${index + 1}", if (index == 0) "working" else "done").joinToString(" · "),
                    color = if (index == 0) colors.foreground else colors.dim,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ThreadWorkspaceHeader(foreground: androidx.compose.ui.graphics.Color) {
        Box(Modifier.fillMaxWidth().height(62.dp), contentAlignment = Alignment.CenterStart) {
            Text("OMARCHY Code", color = foreground, fontSize = 18.sp)
        }
    }

    /** Two chat rows over the field, no cards: the background stays visible between them. */
    @androidx.compose.runtime.Composable
    private fun ChatFixture() {
        Column(Modifier.fillMaxSize().testTag("fixture_chat")) {
            Spacer(Modifier.height(120.dp))
            Text("Reader behind the panel sky", fontSize = 13.sp)
            Spacer(Modifier.height(160.dp))
            Text("Composer and IME keep their insets", fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
        }
    }
}
