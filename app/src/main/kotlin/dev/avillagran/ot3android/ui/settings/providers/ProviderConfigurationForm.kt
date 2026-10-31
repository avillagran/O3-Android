package dev.avillagran.ot3android.ui.settings.providers

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.avillagran.ot3android.R
import org.json.JSONObject

/** Values and secrets are ephemeral. Only native allowlisted fields reach the builder. */
@Composable
internal fun ProviderConfigurationForm(instance: JSONObject?, driver: JSONObject, enabled: Boolean, submit: (JSONObject) -> Unit) {
    var id by remember(instance?.optString("id"), driver.optString("id")) { mutableStateOf(instance?.optString("id").orEmpty()) }
    var label by remember(instance?.toString()) { mutableStateOf(instance?.optString("label").orEmpty()) }
    var color by remember(instance?.toString()) { mutableStateOf(instance?.optString("accentColor").orEmpty()) }
    var active by remember(instance?.toString()) { mutableStateOf(instance?.optBoolean("enabled") ?: true) }
    var confirmation by remember { mutableStateOf<JSONObject?>(null) }
    val values = remember(instance?.toString(), driver.toString()) { mutableStateMapOf<String, String>() }
    val environment = remember(instance?.toString()) { mutableStateListOf<String>().apply {
        val rows = instance?.optJSONArray("environment")
        for (i in 0 until (rows?.length() ?: 0)) add(rows!!.getJSONObject(i).toString())
    } }
    var commandArgs by remember(instance?.toString()) { mutableStateOf<String?>(null) }
    var distribution by remember(instance?.toString()) { mutableStateOf<String?>(null) }
    DisposableEffect(values, environment) { onDispose { values.clear(); environment.clear() } }
    Column {
        Text(driver.optString("label"), style = MaterialTheme.typography.titleMedium)
        if (instance == null) OutlinedTextField(id, { id = it }, enabled = enabled, label = { Text("ID") })
        OutlinedTextField(label, { label = it }, enabled = enabled, label = { Text(stringResource(R.string.ot3_project_rename)) })
        OutlinedTextField(color, { color = it }, enabled = enabled, label = { Text("#RRGGBB") })
        Switch(active, { active = it }, enabled = enabled)
        val fields = driver.optJSONArray("fields")
        for (i in 0 until (fields?.length() ?: 0)) {
            val field = fields!!.getJSONObject(i); val key = field.getString("key")
            val secret = field.optString("control") in setOf("secret", "password")
            val value = values[key] ?: if (secret) "" else instance?.optJSONObject("config")?.optString(key).orEmpty()
            val options = field.optJSONArray("options")
            if (options != null) {
                Text(providerFieldLabel(key))
                for (j in 0 until options.length()) options.getJSONObject(j).let { option ->
                    FilterChip(value == option.getString("id"), { values[key] = option.getString("id") }, enabled = enabled,
                        label = { Text(when (option.optString("id")) {
                            "local" -> stringResource(R.string.ot3_git_local_refs)
                            "oauth-personal" -> "Google (OAuth)"
                            "oauth-business" -> "Gemini Enterprise"
                            "gemini-api-key" -> "Gemini API"
                            "agent-platform" -> "Vertex AI"
                            else -> option.optString("name")
                        }) })
                }
            } else OutlinedTextField(value, { values[key] = it }, enabled = enabled,
                label = { Text(providerFieldLabel(key)) },
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
        }
        if (driver.optString("id") == "acpRegistry") {
            OutlinedTextField(commandArgs.orEmpty(), { commandArgs = it }, enabled = enabled, label = { Text(stringResource(R.string.provider_field_command_args)) })
            Text(stringResource(R.string.provider_field_distribution))
            listOf("auto", "binary", "npx", "uvx").forEach { mode ->
                FilterChip(distribution == mode, { distribution = mode }, enabled = enabled,
                    label = { Text(when (mode) { "auto" -> stringResource(R.string.provider_field_automatic); "binary" -> stringResource(R.string.provider_field_binary_distribution); else -> mode }) })
            }
        }
        Text(stringResource(R.string.provider_field_environment))
        for (index in environment.indices) {
            val row = JSONObject(environment[index])
            OutlinedTextField(row.optString("name"), { environment[index] = JSONObject(environment[index]).put("name", it).toString() },
                enabled = enabled, label = { Text(stringResource(R.string.provider_field_name)) })
            OutlinedTextField(row.optString("value"), { environment[index] = JSONObject(environment[index]).put("value", it).toString() },
                enabled = enabled, label = { Text(stringResource(R.string.provider_field_value)) },
                visualTransformation = if (row.optBoolean("sensitive")) PasswordVisualTransformation() else VisualTransformation.None)
            Row {
                Checkbox(row.optBoolean("sensitive"), { environment[index] = JSONObject(environment[index]).put("sensitive", it).toString() }, enabled = enabled)
                Text(stringResource(R.string.provider_field_sensitive))
            }
            TextButton(enabled = enabled, onClick = { environment.removeAt(index) }) { Text(stringResource(R.string.provider_field_remove_variable)) }
        }
        TextButton(enabled = enabled && environment.size < 64, onClick = { environment.add(JSONObject().put("name", "").put("value", "").put("sensitive", true).toString()) }) { Text(stringResource(R.string.provider_field_add_variable)) }
        TextButton(enabled = enabled && id.isNotBlank(), onClick = {
            val config = JSONObject()
            values.forEach { (key, value) -> config.put(key, value) }
            commandArgs?.let { config.put("commandArgs", org.json.JSONArray(it.lineSequence().filter(String::isNotEmpty).toList())) }
            distribution?.let { config.put("distribution", it) }
            confirmation = JSONObject().put("action", if (instance == null) "create" else "update").put("instanceId", id)
                .put("driver", driver.getString("id")).put("providerEnvironment", org.json.JSONArray(environment.map(::JSONObject))).put("enabled", active).put("accentColor", color).put("config", config)
                .apply { if (label.isNotBlank()) put("label", label) }
        }) { Text(stringResource(R.string.ot3_dialog_save)) }
        if (instance != null && !instance.optBoolean("isDefault")) TextButton(enabled = enabled, onClick = {
            confirmation = JSONObject().put("action", "remove").put("instanceId", id)
        }) { Text(stringResource(R.string.ot3_project_remove)) }
        confirmation?.let { input -> AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(stringResource(R.string.bridge_admin_confirm)) }, text = { Text(id) },
            confirmButton = { TextButton(onClick = { confirmation = null; values.clear(); environment.clear(); commandArgs = null; submit(input) }) { Text(stringResource(R.string.providers_continue)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.providers_cancel)) } }) }
    }
}

@Composable
private fun providerFieldLabel(key: String): String = stringResource(when (key) {
    "binaryPath" -> R.string.provider_field_binary_path
    "homePath" -> R.string.provider_field_home_path
    "shadowHomePath" -> R.string.provider_field_shadow_home_path
    "launchArgs" -> R.string.provider_field_launch_args
    "autoCompactWindow" -> R.string.provider_field_auto_compact_window
    "serverUrl" -> R.string.provider_field_server_url
    "serverPassword" -> R.string.provider_field_server_password
    "authMethod" -> R.string.provider_field_auth_method
    "apiKey" -> R.string.provider_field_api_key
    "gcpProject" -> R.string.provider_field_gcp_project
    "gcpLocation" -> R.string.provider_field_gcp_location
    "source" -> R.string.provider_field_source
    "agentId" -> R.string.provider_field_agent_id
    "commandPath" -> R.string.provider_field_command_path
    "authMethodId" -> R.string.provider_field_auth_method_id
    else -> R.string.providers_interaction_unavailable
})
