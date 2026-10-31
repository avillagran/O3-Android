package dev.avillagran.ot3android.ui.settings.general

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.settings.GeneralClientPreferences
import dev.avillagran.ot3android.core.settings.GeneralSettingsFields
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Standalone P02/P20 contribution. No navigation or connection ownership is assumed here. */
object GeneralSettingsRoute {
    const val id = "general"
    val titleResource = R.string.general_settings_title
    val scoped = true
}

@Composable
fun GeneralSettingsScreen(
    controller: GeneralSettingsController,
    scope: JSONObject,
    clientPreferences: GeneralClientPreferences,
    modifier: Modifier = Modifier,
    query: String = "",
) {
    val state by controller.state.collectAsState()
    val client by clientPreferences.settings.collectAsState(initial = JSONObject())
    val coroutine = rememberCoroutineScope()
    val signature = scope.toString()
    LaunchedEffect(signature) { controller.load(JSONObject(signature)) }
    val page = remember(state.page) { JSONObject(state.page) }
    var localError by remember { mutableStateOf(false) }
    val serverFields = page.optJSONArray("fields").objects().filter { it.optString("key") in GeneralSettingsFields.keys }
    val localFields = clientPreferences.fields(client).objects()
    // Never show a stale scope while LaunchedEffect is waiting to run.
    val current = state.scope == signature
    val writable = current && !state.busy && !state.failed && page.optBoolean("writable")
    LazyColumn(modifier.fillMaxSize().testTag("general_settings"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.general_settings_title), style = MaterialTheme.typography.headlineSmall) }
        item {
            TextButton(enabled = !state.busy, onClick = { coroutine.launch { controller.load(JSONObject(signature)) } }) {
                Text(translated("Refresh"))
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.failed || localError) Text(translated("Unavailable"), color = MaterialTheme.colorScheme.error)
            page.optJSONArray("errors").objects().forEach { error ->
                Text(error.optJSONObject("target")?.optString("environment").orEmpty() + ": " +
                    translated(if (error.optBoolean("uncertain")) "Result unknown. Refresh before retrying." else "Unavailable"),
                    color = MaterialTheme.colorScheme.error)
            }
            if (page.optJSONArray("savedTargets")?.length() != null && page.getJSONArray("savedTargets").length() > 0)
                Text(translated("Saved") + ": " + page.getJSONArray("savedTargets").length())
        }
        items(if (current) serverFields else emptyList(), key = { it.getString("key") }) { field ->
            if (matches(field, query)) Preference(field, writable && field.optBoolean("scopeWritable"), page.optJSONObject("modelCatalog"),
                change = { value -> coroutine.launch { controller.change(field.getString("key"), value) } },
                reset = { coroutine.launch { controller.change(field.getString("key"), field.opt("resetValue")) } },
                inherit = { coroutine.launch { controller.change(field.getString("key"), null, true) } })
        }
        item { Text(translated("This device"), style = MaterialTheme.typography.titleMedium) }
        items(localFields, key = { it.getString("key") }) { field ->
            if (matches(field, query)) Preference(field, true, null,
                change = { value -> coroutine.launch {
                    try { clientPreferences.update(field.getString("key"), value ?: JSONObject.NULL); localError = false }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { localError = true }
                } }, reset = { coroutine.launch { try {
                    clientPreferences.reset(field.getString("key")); localError = false
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { localError = true } } }, inherit = {})
        }
    }
}

@Composable
private fun matches(field: JSONObject, query: String): Boolean = query.isBlank() ||
    listOf(translated(field.optString("label")), translated(field.optString("description")), field.optString("key"))
        .any { it.contains(query.trim(), ignoreCase = true) }

@Composable
private fun Preference(field: JSONObject, enabled: Boolean, catalog: JSONObject?, change: (Any?) -> Unit,
                       reset: () -> Unit, inherit: () -> Unit) {
    val key = field.getString("key")
    val label = if (key.startsWith("quickReplies.")) translated("Quick replies") + " (" + key.substringAfter(".") + ")" else translated(field.optString("label"))
    val value = field.opt("value")
    val mixed = field.optBoolean("mixed")
    Column(Modifier.fillMaxWidth().testTag("general_$key"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        if (field.optString("description").isNotEmpty()) Text(translated(field.getString("description")))
        if (mixed) Text(translated("Mixed values"))
        if (field.optBoolean("projectScoped")) Text(translated(when (field.optString("source")) {
            "project" -> "Project override"; "t3.json" -> "t3.json"; "mixed" -> "Mixed sources"; else -> "Inherited from environment"
        }))
        when (field.optString("kind")) {
            "bool" -> if (mixed) Choices(listOf(JSONObject().put("id", true).put("name", "On"),
                JSONObject().put("id", false).put("name", "Off")), label, enabled, change)
                else Switch(checked = value == true, onCheckedChange = change, enabled = enabled,
                    modifier = Modifier.testTag("general_control_$key").semantics { contentDescription = label })
            "choice" -> Choices(field.optJSONArray("choices").objects(), if (mixed) translated("Mixed values") else
                translated(field.optJSONArray("choices").objects().find { it.opt("id") == value }?.optString("name") ?: "Unavailable"), enabled, change)
            "model" -> {
                val models = catalog?.optJSONArray("models").objects()
                val selected = value as? JSONObject
                Choices(models.filter { it.opt("available") != false }.map { model -> JSONObject()
                    .put("name", model.optString("name", model.optString("model")))
                    .put("id", JSONObject().put("instanceId", model.getString("instanceId")).put("model", model.getString("model")).put("options", JSONArray())) },
                    if (mixed) translated("Mixed values") else selected?.optString("model") ?: translated("Automatic"), enabled, change)
                if (key != "textGenerationModelSelection") TextButton(enabled = enabled, onClick = { change(null) }) { Text(translated("Automatic")) }
                val model = models.find { it.optString("instanceId") == selected?.optString("instanceId") && it.optString("model") == selected.optString("model") }
                model?.optJSONArray("descriptors").objects().forEach { descriptor ->
                    val options = selected?.optJSONArray("options").objects()
                    val id = descriptor.getString("id")
                    val optionValue = options.find { it.optString("id") == id }?.opt("value") ?: descriptor.opt("currentValue")
                    fun updateOption(next: Any?) {
                        val selection = JSONObject(selected.toString())
                        selection.put("options", JSONArray(options.filter { it.optString("id") != id } + JSONObject().put("id", id).put("value", next)))
                        change(selection)
                    }
                    val optionLabel = translated(descriptor.optString("label", id))
                    if (descriptor.optString("type") == "boolean") {
                        Text(optionLabel)
                        Switch(checked = optionValue == true, enabled = enabled, onCheckedChange = { updateOption(it) },
                            modifier = Modifier.semantics { contentDescription = optionLabel })
                    } else if (descriptor.optString("type") == "select") {
                        Choices(descriptor.optJSONArray("options").objects().map { JSONObject(it.toString()).put("name", it.optString("label")) },
                            optionLabel + ": " + (optionValue ?: translated("Automatic")), enabled, ::updateOption)
                    }
                }
            }
            "text", "days" -> {
                var edit by remember(key, value?.toString(), mixed) { mutableStateOf(if (mixed || value == JSONObject.NULL) "" else value?.toString().orEmpty()) }
                OutlinedTextField(value = edit, onValueChange = { if (it.length <= field.optInt("maximumLength", 16384)) edit = it },
                    label = { Text(label) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), singleLine = !field.optBoolean("multiline") && !key.startsWith("quickReplies."))
                TextButton(enabled = enabled && (field.optString("kind") != "days" || edit.toIntOrNull()?.let {
                    it in field.optInt("minimum", 1)..field.optInt("maximum", 90) } == true), onClick = {
                    change(if (field.optString("kind") == "days") edit.toInt() else edit)
                }) { Text(translated("Save")) }
                if (field.optString("kind") == "days") TextButton(enabled = enabled, onClick = { change(null) }) { Text(translated("Off")) }
            }
        }
        Row {
            if (field.optBoolean("projectScoped") && field.optBoolean("overridden")) TextButton(enabled = enabled, onClick = inherit) { Text(translated("Use inherited value")) }
            if (!field.optBoolean("projectScoped") && field.has("resetValue")) TextButton(enabled = enabled, onClick = reset) { Text(translated("Reset")) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun Choices(choices: List<JSONObject>, label: String, enabled: Boolean, change: (Any?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled && choices.isNotEmpty(), onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(translated(choice.optString("name"))) },
                onClick = { expanded = false; change(choice.opt("id")) }) }
        }
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

@Composable
private fun translated(text: String): String = generalTextResources[text]?.let { stringResource(it) } ?: text
