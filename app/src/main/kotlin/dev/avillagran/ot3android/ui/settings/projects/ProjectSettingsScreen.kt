package dev.avillagran.ot3android.ui.settings.projects

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import org.json.JSONObject

/** Route contribution: P20 collects the controller and wires refresh/update callbacks. */
@Composable
fun ProjectSettingsScreen(state: ProjectConfigurationState, onRefresh: () -> Unit,
    onUpdate: (key: String, value: Any?, clear: Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ot3_action_project_settings), style = MaterialTheme.typography.headlineSmall)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
        if (state.needsRefresh) Text(stringResource(R.string.thread_menus_refresh_required))
        TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.thread_menus_refresh)) }
        val fields = state.view?.optJSONArray("fields")
        for (index in 0 until (fields?.length() ?: 0)) {
            val field = fields!!.getJSONObject(index)
            ProjectField(field, state.view?.optJSONObject("catalog"), !state.busy && !state.needsRefresh && state.view?.optBoolean("writeAllowed") == true, onUpdate)
        }
    }
}

@Composable
private fun ProjectField(field: JSONObject, catalog: JSONObject?, writable: Boolean, onUpdate: (String, Any?, Boolean) -> Unit) {
    val key = field.getString("key")
    val value = field.opt("value")
    val enabled = writable && field.optBoolean("scopeWritable")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(field.optString("label", key), style = MaterialTheme.typography.titleSmall)
        Text(field.optString("source"), style = MaterialTheme.typography.labelSmall)
        when (field.optString("kind")) {
            "bool" -> Switch(checked = value == true, onCheckedChange = { onUpdate(key, it, false) }, enabled = enabled)
            "choice" -> {
                val choices = field.optJSONArray("choices")
                for (index in 0 until (choices?.length() ?: 0)) {
                    val choice = choices!!.get(index)
                    val option = (choice as? JSONObject)?.opt("id") ?: choice
                    val label = (choice as? JSONObject)?.optString("name") ?: choice.toString()
                    TextButton(onClick = { onUpdate(key, option, false) }, enabled = enabled && option != value) { Text(label) }
                }
            }
            "text", "days" -> {
                var draft by remember(key, value) { mutableStateOf(if (value == JSONObject.NULL) "" else value?.toString().orEmpty()) }
                OutlinedTextField(draft, { draft = it }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                val input = if (field.optString("kind") == "days") draft.toLongOrNull() else draft
                TextButton(onClick = { onUpdate(key, input, false) }, enabled = enabled && input != null) { Text(stringResource(R.string.ot3_dialog_save)) }
            }
            "model" -> {
                val models = catalog?.optJSONArray("models")
                for (index in 0 until (models?.length() ?: 0)) {
                    val model = models!!.getJSONObject(index)
                    val selection = JSONObject().put("instanceId", model.optString("instanceId")).put("model", model.optString("model"))
                    TextButton(onClick = { onUpdate(key, selection, false) }, enabled = enabled) {
                        Text(model.optString("name", model.optString("model")))
                    }
                }
            }
            // Structured descriptors are read-only until their dedicated editor is registered.
            else -> Text(value?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty())
        }
        TextButton(onClick = { onUpdate(key, null, true) }, enabled = enabled && field.optBoolean("overridden")) {
            Text(stringResource(R.string.thread_menus_inherit))
        }
    }
}
