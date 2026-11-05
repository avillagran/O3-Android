package dev.avillagran.ot3android.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.home.HomeHostStatus
import dev.avillagran.ot3android.ui.kit.HostBadge
import dev.avillagran.ot3android.ui.kit.Ot3Icon
import dev.avillagran.ot3android.ui.kit.Ot3Spinner
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/**
 * Connection progress never replaces the saved list. Each host reports its own phase.
 * It floats over the content (never in the layout flow), so the list or board does not
 * jump when syncing starts or finishes.
 */
@Composable
internal fun HomeConnectionStatus(hosts: List<HomeHostStatus>, modifier: Modifier = Modifier, onOpenEnvironments: () -> Unit) {
    val pending = hosts.filter { it.connection !is ConnectionState.Connected || it.syncing }
    // Keep the last non-empty set while fading out so the pill does not collapse mid-animation.
    var shown by remember { mutableStateOf(pending) }
    if (pending.isNotEmpty()) shown = pending
    AnimatedVisibility(pending.isNotEmpty(), modifier, enter = fadeIn(), exit = fadeOut()) {
    Surface(Modifier.testTag("home_connection_status"), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f), shadowElevation = 4.dp,
        border = BorderStroke(1.dp, Ot3Theme.colors.composerBorder)) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val pending = shown
        if (pending.any { it.hasSavedThreads }) Text(stringResource(R.string.home_saved_threads),
            style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            pending.forEach { host ->
                val connected = host.connection is ConnectionState.Connected
                val reconnecting = host.connection == ConnectionState.Connecting || (host.connection as? ConnectionState.Unreachable)?.nextRetryAt != null
                val progress = connected || reconnecting
                Row(Modifier.heightIn(min = 36.dp).testTag("home_host_status_${host.id}").clickable(onClick = onOpenEnvironments),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HostBadge(host.label, host.id)
                    if (progress) Ot3Spinner(Modifier.testTag("home_host_progress_${host.id}"), size = 12.dp) else Ot3Icon("cloud", null, size = 12.dp)
                    Text(stringResource(when {
                        connected && host.hasSavedThreads -> R.string.mobile_syncing_threads
                        connected -> R.string.mobile_loading_threads
                        reconnecting -> R.string.mobile_connecting_environment
                        host.connection == ConnectionState.Unauthorized -> R.string.connect_error_unauthorized
                        else -> R.string.ot3_connection_offline
                    }), color = Ot3Theme.colors.dim, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
    }
    }
