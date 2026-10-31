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
    onMutation: ((JSONObject) -> Unit)? = null, onRetryMutation: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ot3_action_project_settings), style = MaterialTheme.typography.headlineSmall)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
        if (state.needsRefresh) Text(stringResource(R.string.thread_menus_refresh_required))
        TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.thread_menus_refresh)) }
        if (onRetryMutation != null) TextButton(onClick = onRetryMutation, enabled = !state.busy) { Text(stringResource(R.string.thread_resend)) }
        if (onMutation != null && state.metadata != null) {
            val metadataEnabled = state.canMutate && !state.busy && !state.needsRefresh
            var title by remember(state.metadata.toString()) { mutableStateOf(state.metadata.optString("title")) }
            var remove by remember { mutableStateOf(false) }
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.ot3_project_rename)) }, enabled = metadataEnabled)
            TextButton(enabled = metadataEnabled && title.isNotBlank(), onClick = { onMutation(JSONObject().put("action", "project-rename").put("title", title)) }) { Text(stringResource(R.string.ot3_dialog_save)) }
            TextButton(enabled = metadataEnabled, onClick = { onMutation(JSONObject().put("action", "project-settings").put("patch", JSONObject().put("autoPull", !state.metadata.optBoolean("autoPull")))) }) { Text(stringResource(R.string.ot3_project_auto_pull)) }
            listOf("local", "worktree").forEach { mode ->
                TextButton(enabled = metadataEnabled, onClick = { onMutation(JSONObject().put("action", "project-settings").put("patch", JSONObject().put("defaultThreadEnvMode", mode))) }) { Text(stringResource(if (mode == "worktree") R.string.general_text_55 else R.string.ot3_new_chat_current_checkout)) }
            }
            var modelQuery by remember { mutableStateOf("") }
            OutlinedTextField(modelQuery, { modelQuery = it }, label = { Text(stringResource(R.string.composer_search_models)) }, enabled = metadataEnabled)
            state.metadata.optJSONObject("defaultModelSelection")?.let { selection -> Text(selection.optString("instanceId") + " · " + selection.optString("model")) }
            val choices = state.view?.optJSONObject("catalog")?.optJSONArray("models")
            (0 until (choices?.length() ?: 0)).map { choices!!.getJSONObject(it) }
                .filter { it.optString("name").contains(modelQuery, ignoreCase = true) && it.optBoolean("available", true) }.take(40).forEach { model ->
                    TextButton(enabled = metadataEnabled, onClick = {
                        val selection = JSONObject().put("instanceId", model.getString("instanceId")).put("model", model.getString("model")).put("options", org.json.JSONArray())
                        onMutation(JSONObject().put("action", "project-settings").put("patch", JSONObject().put("defaultModelSelection", selection)))
                    }) { Text(model.optString("name", model.optString("model"))) }
                }
            TextButton(enabled = metadataEnabled && state.metadata.optJSONObject("defaultModelSelection") != null, onClick = {
                onMutation(JSONObject().put("action", "project-settings").put("patch", JSONObject().put("defaultModelSelection", JSONObject.NULL)))
            }) { Text(stringResource(R.string.thread_menus_inherit)) }
            TextButton(enabled = metadataEnabled, onClick = { remove = true }) { Text(stringResource(R.string.ot3_project_remove)) }
            if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text(stringResource(R.string.ot3_project_remove)) }, text = { Text(stringResource(R.string.ot3_project_remove_body)) },
                confirmButton = { TextButton(onClick = { remove = false; onMutation(JSONObject().put("action", "project-remove").put("confirmed", true)) }) { Text(stringResource(R.string.ot3_project_remove)) } },
                dismissButton = { TextButton(onClick = { remove = false }) { Text(stringResource(R.string.ot3_dialog_cancel)) } })
        }
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
