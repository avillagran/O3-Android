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

val LocalThreadSidebarControl = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }
val LocalSelectedThreadKey = staticCompositionLocalOf<String?> { null }

/** The chat stays at the same composition position when the sidebar changes size. */
@Composable
fun ThreadWorkspace(sidebar: @Composable () -> Unit, chat: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val split = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || configuration.smallestScreenWidthDp >= 600
    val sidebarState = rememberSaveableStateHolder()
    var collapsed by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(if (split && !collapsed) 280.dp else 0.dp).fillMaxHeight().testTag("thread_sidebar")) {
            if (split && !collapsed) sidebarState.SaveableStateProvider("sidebar") { sidebar() }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val control: (@Composable () -> Unit)? = if (split) {
                {
                    TextButton(
                        onClick = { collapsed = !collapsed },
                        modifier = Modifier.testTag("thread_sidebar_toggle"),
                    ) {
                        Text(stringResource(if (collapsed) R.string.mobile_show_sidebar else R.string.mobile_hide_sidebar))
                    }
                }
            } else null
            CompositionLocalProvider(LocalThreadSidebarControl provides control) { chat() }
        }
    }
}
