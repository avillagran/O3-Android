package dev.avillagran.ot3android.ui.navigation

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R

val LocalSelectedThreadKey = staticCompositionLocalOf<String?> { null }

/** The chat stays at the same composition position when the sidebar changes size. */
@Composable
fun ThreadWorkspace(sidebar: @Composable () -> Unit, chat: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val split = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || configuration.smallestScreenWidthDp >= 600
    val sidebarState = rememberSaveableStateHolder()
    var collapsed by rememberSaveable { mutableStateOf(false) }
    // Workspace chrome must survive loading/error and screens without a toolbar.
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val shown = if (split) !collapsed else drawerOpen
    androidx.activity.compose.BackHandler(enabled = !split && drawerOpen) { drawerOpen = false }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))) {
        TextButton(
            onClick = { if (split) collapsed = !collapsed else drawerOpen = !drawerOpen },
            modifier = Modifier.heightIn(min = 48.dp).testTag("thread_sidebar_toggle"),
        ) {
            Text(stringResource(if (shown) R.string.mobile_hide_sidebar else R.string.mobile_show_sidebar))
        }
        Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxSize()) {
                SidebarReveal(shown = split && shown, modifier = Modifier.testTag("thread_sidebar")) {
                    sidebarState.SaveableStateProvider("sidebar") { sidebar() }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { chat() }
            }
            // Overlay: opening the portrait panel never recomposes the reader in another slot.
            if (!split && drawerOpen) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = dev.avillagran.ot3android.ui.theme.Ot3Theme.colors.background) {
                    sidebarState.SaveableStateProvider("sidebar") { sidebar() }
                }
            }
        }
    }
}
