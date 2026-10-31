package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.transport.NativeParity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal val LocalReaderReference = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Authenticated bounded source viewer: HTML is inert selectable source, never a WebView. */
@Composable
internal fun ReaderReferenceHost(connection: Connection?, threadId: String, localEnvironment: String,
    graph: dev.avillagran.ot3android.AppGraph, openThread: (String, String) -> Unit, content: @Composable () -> Unit) {
    val worker = rememberCoroutineScope()
    var document by remember(connection) { mutableStateOf<String?>(null) }
    var failed by remember(connection) { mutableStateOf(false) }
    var busy by remember(connection) { mutableStateOf(false) }
    val current by rememberUpdatedState(connection)
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val route: (String) -> Unit = { href ->
        val captured = connection
        if (captured != null && !busy) {
            busy = true; failed = false
            worker.launch {
                try {
                    val environments = JSONArray(graph.connectionManager.supervisors.value.map { (id, supervisor) ->
                        JSONObject().put("id", id).put("connected", supervisor.connection.value != null)
                            .put("threads", JSONArray(supervisor.shell.value?.threads.orEmpty().map { JSONObject().put("id", it.id) }))
                    })
                    val target = NativeParity().reference(href, localEnvironment, threadId, environments)
                    if (current === captured) when (target.getString("kind")) {
                        "thread" -> openThread(target.getString("environmentId"), target.getString("threadId"))
                        "document" -> {
                            val result = captured.transport.document(target.getJSONObject("resource"))
                            if (current === captured) document = result.getString("contents")
                        }
                        "external" -> uri.openUri(target.getString("url"))
                        else -> failed = true
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (current === captured) failed = true }
                finally { if (current === captured) busy = false }
            }
        }
    }
    CompositionLocalProvider(LocalReaderReference provides if (connection != null) route else null) { content() }
    if (document != null || failed || busy) AlertDialog(onDismissRequest = { document = null; failed = false },
        title = { Text(stringResource(R.string.ot3_inspector_details)) }, text = {
            if (busy) CircularProgressIndicator()
            else if (failed) Text(stringResource(R.string.ot3_inspector_unavailable_error))
            else SelectionContainer { Text(document.orEmpty(), Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
        }, confirmButton = { TextButton(onClick = { document = null; failed = false }) { Text(stringResource(R.string.ot3_dialog_close)) } })
}
