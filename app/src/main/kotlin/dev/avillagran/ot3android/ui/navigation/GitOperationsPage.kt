package dev.avillagran.ot3android.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.ConnectedAppTransport
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.core.git.*
import dev.avillagran.ot3android.ui.git.GitControlsSurface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun GitOperationsPage(navigator: Ot3Navigator, route: ThreadGit,
    connections: dev.avillagran.ot3android.core.connection.ConnectionManager = LocalContext.current.appGraph.connectionManager,
    journal: GitJournal = LocalContext.current.appGraph.gitJournal,
    admission: CheckoutAdmission = LocalContext.current.appGraph.checkoutAdmission,
    outbox: dev.avillagran.ot3android.core.thread.OutboxRepository = LocalContext.current.appGraph.outboxRepository,
) {
    val connection by remember(route.environmentId) { connections.connectionFlow(route.environmentId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val current by rememberUpdatedState(connection)
    val scope = rememberCoroutineScope()
    key(connection, route) {
        val captured = connection
        val operations = remember { GitOperations(journal, stackedAction =
            (captured?.transport as? ConnectedAppTransport)?.let { transport -> NativeGitStackedAction({ id ->
                transport.nativeClient.takeIf { id == route.environmentId && current === captured }
            }) }) }
        val target = remember { JSONObject().put("environment", route.environmentId).put("thread", route.threadId) }
        var snapshot by remember { mutableStateOf<JSONObject?>(null) }
        var record by remember { mutableStateOf<JSONObject?>(journal.records().lastOrNull {
            it.optJSONObject("plan")?.optJSONObject("scope")?.optString("environment") == route.environmentId &&
                it.optString("state") !in setOf("complete", "failed", "reviewed")
        }) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var permissions by remember { mutableStateOf(emptySet<String>()) }
        fun refresh() {
            if (loading || captured == null) return
            loading = true; error = null
            scope.launch {
                try {
                    val session = captured.transport.authSession()
                    val values = session.optJSONArray("permissions") ?: session.optJSONArray("scopes")
                    val access = if (session.optBoolean("authenticated") && values != null)
                        (0 until values.length()).map { values.getString(it) }.toSet() else emptySet()
                    val result = operations.read(captured.transport, target)
                    if (current === captured) { snapshot = result; permissions = access }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { error = "git_read_failed" }
                finally { loading = false }
            }
        }
        LaunchedEffect(captured) { refresh() }
        androidx.activity.compose.BackHandler { navigator.back() }
        Column {
        androidx.compose.material3.TextButton(onClick = { navigator.back() }) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(dev.avillagran.ot3android.R.string.action_back))
        }
        GitControlsSurface(snapshot, captured != null, "source-control:write" in permissions,
            "terminal:write" in permissions, operations.supportsStackedActions, record, loading, error,
            onRefresh = ::refresh, onConfirm = { intent ->
                if (!loading && captured != null) {
                    loading = true; error = null
                    scope.launch {
                        try {
                            admission.git(route.environmentId,
                                { outbox.observeEnvironment(route.environmentId).first().isEmpty() }) {
                                check(current === captured) { "git_connection_changed" }
                                record = operations.confirm(captured.transport, snapshot!!, intent)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) {
                            record = journal.find(intent.getString("actionId"))
                            error = "git_write_unconfirmed"
                        } finally { loading = false }
                    }
                }
            }, onReview = { path -> navigator.push(ThreadChanges(route.environmentId, route.threadId, "working-tree", path)) },
            onOpenNative = null, onReconcile = { id ->
                if (journal.reviewed(id, route.environmentId, true)) { record = journal.find(id); refresh() }
            }, modifier = Modifier.weight(1f))
        }
    }
}
