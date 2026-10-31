package dev.avillagran.ot3android.ui.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.core.terminal.TerminalBindings

/** Fail closed until the pinned library exposes native cwd resolution and output reduction. */
@Composable
internal fun TerminalPage(environmentId: String, threadId: String, onClose: () -> Unit) {
    val connections = LocalContext.current.appGraph.connectionManager
    val connection by remember(environmentId) { connections.connectionFlow(environmentId) }
        .collectAsStateWithLifecycle(initialValue = null)
    key(environmentId, threadId, connection) {
        var access by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(connection) {
            access = try { connection?.transport?.authSession()?.let(TerminalBindings::canOperate) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        androidx.activity.compose.BackHandler(onBack = onClose)
        TerminalUnavailable(environmentId, threadId, access, onClose)
    }
}

@Composable
internal fun TerminalUnavailable(environmentId: String, threadId: String, canOperate: Boolean?, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp).testTag("terminal_unavailable")) {
        Text(stringResource(R.string.terminal_title), style = MaterialTheme.typography.titleLarge)
        Text(environmentId, style = MaterialTheme.typography.bodySmall)
        Text(threadId, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.terminal_bindings_missing))
        if (canOperate == false) Text(stringResource(R.string.terminal_read_only_unavailable))
        if (canOperate == null) Text(stringResource(R.string.terminal_disconnected))
        TextButton(onClick = onClose, modifier = Modifier.testTag("terminal_close")) {
            Text(stringResource(R.string.terminal_close))
        }
    }
}
