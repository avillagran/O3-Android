package dev.avillagran.ot3android.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.core.git.GitReviewReader
import dev.avillagran.ot3android.ui.git.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun WorkingChangesPage(navigator: Ot3Navigator, route: ThreadChanges) {
    val graph = LocalContext.current.appGraph
    val connection by remember(route.environmentId) { graph.connectionManager.connectionFlow(route.environmentId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val current by rememberUpdatedState(connection)
    val target = remember(route) { JSONObject().put("environment", route.environmentId).put("thread", route.threadId) }
    val reader = remember { GitReviewReader() }
    val settings by graph.diffReviewSettings.collectAsStateWithLifecycle()
    var refs by remember(connection, route) { mutableStateOf<JSONObject?>(null) }
    var generation by remember { mutableLongStateOf(0) }
    val coroutine = rememberCoroutineScope()
    key(connection, route) {
        GitWorkingReviewDialog(target, read = { source, base, whitespace, path ->
            val captured = current
            val result = captured?.let { reader.read(it.transport, target, source, base, whitespace, path) }
            result.takeIf { captured != null && current == captured }
        }, refs = refs, onRefsRequested = { query, kind, cursor ->
            val issued = ++generation
            val captured = current
            refs = null
            coroutine.launch {
                try {
                    val result = captured?.let { reader.refs(it.transport, target, query, kind, cursor) }
                    if (issued == generation && captured == current) refs = result
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { if (issued == generation) refs = null }
            }
        }, settings = settings, onSettingsChange = {
            graph.setDiffReviewSettings(it)
        }, onClose = { navigator.back() })
    }
}
