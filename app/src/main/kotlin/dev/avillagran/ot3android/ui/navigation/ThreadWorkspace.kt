package dev.avillagran.ot3android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R

val LocalWorkspaceSidebarAction = staticCompositionLocalOf { false }

val LocalSelectedThreadKey = staticCompositionLocalOf<String?> { null }

/** The chat stays at the same composition position when the sidebar changes size. */
@Composable
fun ThreadWorkspace(sidebar: @Composable () -> Unit, chat: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val configuration = LocalConfiguration.current
    val split = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || configuration.smallestScreenWidthDp >= 600
    val sidebarState = rememberSaveableStateHolder()
    var collapsed by rememberSaveable { mutableStateOf(false) }
    // Workspace chrome must survive loading/error and screens without a toolbar.
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val shown = if (split) !collapsed else drawerOpen
    androidx.activity.compose.BackHandler(enabled = !split && drawerOpen) { drawerOpen = false }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                if (split) SidebarReveal(shown = shown, modifier = Modifier.testTag("thread_sidebar")) {
                    sidebarState.SaveableStateProvider("sidebar") { sidebar() }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    CompositionLocalProvider(LocalWorkspaceSidebarAction provides true) { chat() }
                }
            }
            // Overlay: opening the portrait panel never recomposes the reader in another slot.
            if (!split) {
                if (drawerOpen) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .25f))
                    .clickable { drawerOpen = false }.semantics { contentDescription = "Close sidebar" })
                SidebarReveal(shown = drawerOpen, modifier = Modifier.testTag("thread_sidebar_drawer")) {
                    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = dev.avillagran.ot3android.ui.theme.Ot3Theme.colors.background) {
                        sidebarState.SaveableStateProvider("sidebar") { sidebar() }
                    }
                }
            }
        }
        // Always reachable, even if chat content has no toolbar. The real toolbar
        // reserves this action slot instead of adding another 48 dp header row.
        IconButton(
            onClick = {
                focus.clearFocus()
                keyboard?.hide()
                if (split) collapsed = !collapsed else drawerOpen = !drawerOpen
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp)
                .size(48.dp).testTag("thread_sidebar_toggle"),
        ) {
            Icon(painterResource(if (shown) R.drawable.ic_panel_left_close else R.drawable.ic_panel_left),
                stringResource(if (shown) R.string.mobile_hide_sidebar else R.string.mobile_show_sidebar))
        }
    }
}
