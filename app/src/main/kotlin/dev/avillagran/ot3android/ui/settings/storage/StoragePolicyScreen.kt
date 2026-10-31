package dev.avillagran.ot3android.ui.settings.storage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.maintenance.StoragePolicyController
import dev.avillagran.ot3android.ui.settings.maintenance.objects
import dev.avillagran.ot3android.ui.settings.maintenance.maintenanceText
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Native environment or explicit-project policy descriptors. */
@Composable
fun StoragePolicyScreen(controller: StoragePolicyController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val coroutine = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.refresh() }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val page = remember(state.data) { JSONObject(state.data) }
    val writable = page.optBoolean("writable") && !state.busy && !state.stale && !state.unknown
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(controller.host.environment)
        Text(stringResource(R.string.maintenance_host_only))
        TextButton(enabled = !state.busy, onClick = { coroutine.launch { controller.refresh() } }) { Text(stringResource(R.string.maintenance_refresh)) }
        if (state.unknown) Text(stringResource(R.string.maintenance_unknown))
        if (state.failed || state.stale) Text(stringResource(R.string.maintenance_failed))
        if (!state.busy && page.optJSONArray("fields").objects().none { it.optString("key").let { key -> key == "worktreeCleanup" || key == "worktreesDirectory" || key.startsWith("storageCleanup.") } }) Text(stringResource(R.string.maintenance_unavailable))
        page.optJSONArray("fields").objects().filter { it.optString("key").let { key -> key == "worktreeCleanup" || key == "worktreesDirectory" || key.startsWith("storageCleanup.") } }.forEach { field ->
            val key = field.getString("key")
            val enabled = writable && field.optBoolean("scopeWritable")
            val value = field.opt("value")
            Text(maintenanceText(field.optString("label")))
            if (field.optBoolean("mixed")) Text(maintenanceText("Mixed values"))
            fun change(next: Any?) { coroutine.launch {
                try { controller.change(key, next) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: IllegalStateException) { controller.refresh() }
            } }
            when (field.optString("kind")) {
                "worktree-policy" -> ProjectWorktreePolicy(field, enabled, ::change)
                "bool" -> Row {
                    TextButton(enabled = enabled, onClick = { change(true) }) { Text(maintenanceText("On")) }
                    TextButton(enabled = enabled, onClick = { change(false) }) { Text(maintenanceText("Off")) }
                    Text(if (value == true) maintenanceText("On") else maintenanceText("Off"))
                }
                "choice" -> {
                    Text(maintenanceText(field.optJSONArray("choices").objects().find { it.opt("id") == value }?.optString("name") ?: "Unavailable"))
                    field.optJSONArray("choices").objects().forEach { choice ->
                        TextButton(enabled = enabled, onClick = { change(choice.opt("id")) }) { Text(maintenanceText(choice.optString("name"))) }
                    }
                }
                "days", "text" -> {
                    var edit by remember(key, value?.toString()) { mutableStateOf(if (value == JSONObject.NULL) "" else value?.toString().orEmpty()) }
                    OutlinedTextField(edit, { edit = it }, enabled = enabled, label = { Text(maintenanceText(field.optString("label"))) })
                    val days = field.optString("kind") == "days"
                    val valid = !days || edit.toIntOrNull()?.let { it in field.optInt("minimum", 1)..field.optInt("maximum", 3650) } == true
                    TextButton(enabled = enabled && valid, onClick = { change(if (days) edit.toInt() else edit) }) { Text(stringResource(R.string.maintenance_save)) }
                    if (days) TextButton(enabled = enabled, onClick = { change(null) }) { Text(maintenanceText("Off")) }
                }
            }
            if (field.has("resetValue")) TextButton(enabled = enabled, onClick = { change(field.opt("resetValue")) }) { Text(maintenanceText("Reset")) }
            HorizontalDivider()
        }
    }
}
