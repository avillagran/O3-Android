package dev.avillagran.ot3android.ui.settings.maintenance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.maintenance.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** P20 owns navigation and connection lifetime. This contribution never selects or switches hosts. */
object MaintenanceRoutes {
    val pages = MaintenancePage.entries.filter { it.read != null }.associateWith { "settings/maintenance/${it.name.lowercase()}" }
}

@Composable
fun MaintenanceScreen(controller: MaintenanceController, modifier: Modifier = Modifier, newTaskRecipe: JSONObject? = null, onImportProjectRequired: ((String) -> Unit)? = null) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmation by remember(controller) { mutableStateOf<Pair<String, String>?>(null) }
    var editing by remember(controller) { mutableStateOf<String?>(null) }
    LaunchedEffect(controller) { controller.refresh() }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val data = remember(state.data) { JSONObject(state.data) }
    val enabled = !state.busy && !state.stale && !state.unknown
    fun request(fn: String, args: JSONObject = JSONObject()) { confirmation = fn to args.toString() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("maintenance_page"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(title(controller.page)), style = MaterialTheme.typography.titleLarge)
        Text(controller.host.environment)
        Text(stringResource(R.string.maintenance_host_only))
        TextButton(onClick = { scope.launch { controller.refresh() } }, enabled = !state.busy) { Text(stringResource(R.string.maintenance_refresh)) }
        if (state.busy) CircularProgressIndicator()
        if (state.unknown) Text(stringResource(R.string.maintenance_unknown), modifier = Modifier.testTag("maintenance_unknown"))
        if (state.failed) Text(stringResource(R.string.maintenance_failed))
        if (state.stale && state.readable) Text(stringResource(R.string.maintenance_stale))
        if (!state.readable && !state.busy) Text(stringResource(R.string.maintenance_unavailable))
        when (controller.page) {
            MaintenancePage.MEMORY -> Text(stringResource(R.string.maintenance_memory_gap))
            MaintenancePage.VAULT -> Text(stringResource(R.string.maintenance_vault_gap))
            MaintenancePage.HOST_SYNC -> Text(stringResource(R.string.maintenance_sync_gap))
            MaintenancePage.STORAGE -> {
                val report = data.optJSONObject("report") ?: data
                Text(stringResource(R.string.maintenance_report, report.optString("finishedAt", "—"), report.optString("bytesFreed", "—")))
                report.optJSONObject("counts")?.let { counts ->
                    Text(stringResource(R.string.maintenance_counts, counts.optInt("removed"), counts.optInt("kept"), counts.optInt("failed")))
                }
                report.optJSONArray("entries").objects().take(200).forEach { row ->
                    Text(listOf("path", "outcome", "reason").joinToString(" · ") { row.optString(it).take(1024) })
                }
                Action(R.string.maintenance_cleanup, "server.runStorageCleanup", state, enabled) { request(it) }
            }
            MaintenancePage.ARCHIVE -> {

                data.optJSONArray("threads").objects().forEach { row ->
                    Text(row.optString("title").take(1000))
                    listOf(R.string.maintenance_restore to "thread.unarchive", R.string.maintenance_delete to "thread.delete").forEach { (label, type) ->
                        Action(label, "orchestration.dispatchCommand", state, enabled) {
                            request(it, JSONObject().put("command", JSONObject().put("type", type).put("threadId", row.getString("id"))))
                        }
                    }
                }
            }
            MaintenancePage.SCHEDULES -> {
                if (newTaskRecipe != null && "scheduledTasks.upsert" in state.writable) TextButton(enabled = enabled,
                    onClick = { editing = JSONObject(newTaskRecipe.toString()).apply { remove("id"); remove("commandId") }.toString() }) { Text(maintenanceText("New task")) }
                data.optJSONArray("tasks").objects().forEach { task ->
                HorizontalDivider()
                Text(task.optString("title").take(1000))
                Text(task.optJSONObject("schedule")?.let { schedule ->
                    listOf("type", "everyMs", "timeOfDay", "weekdays").filter(schedule::has).joinToString(" · ") { schedule.opt(it)?.toString().orEmpty() }
                } ?: "—")
                val id = JSONObject().put("id", task.optString("id"))
                Action(if (task.optBoolean("enabled")) R.string.maintenance_disable else R.string.maintenance_enable,
                    "scheduledTasks.setEnabled", state, enabled) { request(it, JSONObject(id.toString()).put("enabled", !task.optBoolean("enabled"))) }
                Action(R.string.maintenance_run, "scheduledTasks.runNow", state, enabled) { request(it, id) }
                Action(R.string.maintenance_delete, "scheduledTasks.delete", state, enabled) { request(it, id) }
                if ("scheduledTasks.upsert" in state.writable) TextButton(onClick = { editing = task.toString() }, enabled = enabled) { Text(stringResource(R.string.maintenance_edit)) }
            }
            }
            MaintenancePage.SESSIONS -> {
                if (data.optBoolean("truncated")) Text(stringResource(R.string.maintenance_truncated))
                data.optJSONArray("candidates").objects().forEach { row ->
                    Text(row.optString("title").take(1000)); Text(row.optString("path").take(4096))
                    if (row.optString("projectId").isNotBlank() && row.optString("projectId") != "null") {
                        Action(R.string.maintenance_import, "agentSessions.import", state, enabled) {
                            request(it, JSONObject().put("projectId", row.getString("projectId")).put("expectedWorkspaceRoot", row.getString("path")))
                        }
                    } else if (onImportProjectRequired != null) {
                        TextButton(enabled = enabled, onClick = { onImportProjectRequired(row.optString("path")) }) { Text(maintenanceText("Add project")) }
                    }
                }
            }
            MaintenancePage.DIAGNOSTICS -> {
                ProcessHistory(data.optJSONObject("telemetry")?.optJSONArray("buckets"))
                if (data.optBoolean("telemetryUnavailable")) Text(stringResource(R.string.maintenance_unavailable))

                data.optJSONArray("processes").objects().take(500).forEach { row ->
                    Text("  ".repeat(row.optInt("depth").coerceIn(0, 4)) + row.optString("command").take(300))
                    Text(stringResource(R.string.maintenance_process, row.optString("pid"), row.optString("cpuPercent"), row.optString("rssBytes")))
                    if (row.has("startTimeMs") && row.optLong("pid") > 1) {
                        listOf(R.string.maintenance_interrupt to "SIGINT", R.string.maintenance_kill to "SIGKILL").forEach { (label, signal) ->
                            Action(label, "server.signalProcess", state, enabled) {
                                request(it, JSONObject().put("pid", row.getLong("pid")).put("startTimeMs", row.getLong("startTimeMs")).put("signal", signal))
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { value -> TaskEditor(JSONObject(value), onDismiss = { editing = null }) { args ->
        editing = null; request("scheduledTasks.upsert", args)
    } }
    confirmation?.let { (fn, args) ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(R.string.maintenance_confirm)) },
            text = { Text(controller.host.environment + "\n" + stringResource(R.string.maintenance_confirm_body)) },
            confirmButton = { TextButton(enabled = enabled && fn in state.writable, onClick = {
                confirmation = null
                scope.launch {
                    try { controller.mutate(fn, JSONObject(args), confirmed = true) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: IllegalStateException) { /* A second tap or revoked access requires a fresh read. */ }
                    controller.refresh()
                }
            }) { Text(stringResource(R.string.maintenance_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.maintenance_cancel)) } })
    }
}

@Composable
private fun Action(label: Int, fn: String, state: MaintenanceState, enabled: Boolean, onClick: (String) -> Unit) {
    if (fn in state.writable) TextButton(onClick = { onClick(fn) }, enabled = enabled) { Text(stringResource(label)) }
}
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)
internal fun title(page: MaintenancePage): Int = when (page) {
    MaintenancePage.MEMORY -> R.string.maintenance_memory
    MaintenancePage.STORAGE -> R.string.maintenance_storage
    MaintenancePage.ARCHIVE -> R.string.maintenance_archive
    MaintenancePage.SCHEDULES -> R.string.maintenance_schedules
    MaintenancePage.SESSIONS -> R.string.maintenance_sessions
    MaintenancePage.DIAGNOSTICS -> R.string.maintenance_diagnostics
    MaintenancePage.VAULT -> R.string.maintenance_vault
    MaintenancePage.HOST_SYNC -> R.string.maintenance_sync
}
