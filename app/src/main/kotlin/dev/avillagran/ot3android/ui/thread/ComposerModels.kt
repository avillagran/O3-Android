package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.ot3android.ui.kit.Ot3Sheet
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import dev.avillagran.ot3android.core.providers.ModelPresentation

/** Native catalog rows and descriptor values; no provider-name/driver inference. */
@Composable
internal fun ComposerModels(
    catalogJson: String?, selectionJson: String?, loading: Boolean, enabled: Boolean,
    onRefresh: () -> Unit, onSelect: (JSONObject) -> Unit,
    onOption: (String, Any) -> Unit, onFavorite: (JSONObject, Boolean) -> Unit,
    permissions: @Composable () -> Unit = {},
) {
    val catalog = remember(catalogJson) { catalogJson?.let(::JSONObject) ?: JSONObject() }
    val selection = remember(selectionJson) { selectionJson?.let(::JSONObject) ?: JSONObject() }
    val models = catalog.optJSONArray("models").objects()
    val providers = catalog.optJSONArray("providers").objects().ifEmpty {
        models.distinctBy { it.optString("instanceId") }.map { row -> JSONObject()
            .put("instanceId", row.optString("instanceId")).put("name", row.optString("providerName"))
            .put("driverKind", row.optString("driverKind")).put("ready", row.optBoolean("available")) }
    }
    val favorites = catalog.optJSONArray("favorites").objects()
    val selected = models.firstOrNull {
        it.optString("instanceId") == selection.optString("instanceId") && it.optString("model") == selection.optString("model")
    }
    var picker by rememberSaveable { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    var provider by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var legacy by rememberSaveable { mutableStateOf(false) }
    fun favorite(row: JSONObject) = favorites.any {
        it.optString("provider") == row.optString("instanceId") && it.optString("model") == row.optString("model")
    }
    val colors = Ot3Theme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val modelLabel = stringResource(R.string.composer_model) + ": " + selection.optString("model")
        IconButton(onClick = {
            provider = if (favorites.isNotEmpty()) "favorites" else selection.optString("instanceId")
            search = ""; legacy = selected?.optBoolean("isLegacy") == true; picker = true; onRefresh()
        }, enabled = enabled, modifier = Modifier.size(48.dp).testTag("composer_models").semantics { contentDescription = modelLabel }) {
            if (selected != null) ProviderIcon(selected, colors.dim)
            else dev.avillagran.ot3android.ui.kit.Ot3Icon("bot", null)
        }
        permissions()
        Ot3IconButton("sliders-horizontal", stringResource(R.string.composer_options),
            { options = true; onRefresh() }, enabled = enabled && selection.has("model"))
    }
    if (picker) Ot3Sheet(onDismissRequest = { picker = false }, title = stringResource(R.string.composer_model)) {
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            singleLine = true, placeholder = { Text(stringResource(R.string.composer_search_models)) })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp)) {
            LazyColumn(Modifier.width(88.dp).fillMaxHeight()) {
                item { TextButton(onClick = { provider = "favorites" }) { Text(stringResource(R.string.composer_favorites)) } }
                items(providers, key = { it.getString("instanceId") }) { row ->
                    TextButton(onClick = { provider = row.getString("instanceId"); search = "" }, enabled = enabled && row.optBoolean("ready", true),
                        modifier = Modifier.testTag("model_provider_${row.getString("instanceId")}")) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ProviderIcon(row, if (provider == row.optString("instanceId")) colors.accent else colors.dim)
                            Text(row.optString("name"), color = if (provider == row.optString("instanceId")) colors.accent else colors.dim,
                                style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            val visible = models.filter { row ->
                row.optBoolean("available") &&
                    (if (provider == "favorites") favorite(row) else search.isNotBlank() || provider.isEmpty() || row.optString("instanceId") == provider) &&
                    (search.isNotBlank() || provider == "favorites" || legacy || !row.optBoolean("isLegacy")) &&
                    ModelPresentation.search(row, search) >= 0
            }.sortedWith(compareByDescending<JSONObject> { ModelPresentation.search(it, search) }
                .thenByDescending { provider != "favorites" && favorite(it) })
            LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                if (visible.isEmpty()) item { Text(stringResource(R.string.composer_no_models), Modifier.padding(16.dp), color = colors.dim) }
                items(visible, key = { it.getString("instanceId") + ":" + it.getString("model") }) { row ->
                    val current = selected?.let { it.optString("instanceId") == row.optString("instanceId") && it.optString("model") == row.optString("model") } == true
                    Row(Modifier.fillMaxWidth().testTag("model_result_${row.optString("instanceId")}:${row.optString("model")}").clickable(enabled && !loading && row.optBoolean("available")) {
                        onSelect(row); picker = false
                    }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProviderIcon(row, if (current) colors.accent else colors.dim, Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(row.optString("displayName"), color = if (current) colors.accent else colors.foreground,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(row.optString("providerName"), color = colors.dim, style = MaterialTheme.typography.labelSmall)
                        }
                        val isFavorite = favorite(row)
                        Ot3IconButton(if (isFavorite) "star-filled" else "star", stringResource(if (isFavorite) R.string.composer_remove_favorite else R.string.composer_add_favorite),
                            { onFavorite(row, !isFavorite) }, enabled = enabled && !loading && catalog.optBoolean("favoritesReady"),
                            tint = if (isFavorite) colors.waiting else colors.dim)
                    }
                }
                item { TextButton(onClick = { legacy = !legacy }) { Text(stringResource(R.string.composer_legacy_models)) } }
            }
        }
    }
    if (options) Ot3Sheet(onDismissRequest = { options = false }, title = stringResource(R.string.composer_options)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val descriptors = selected?.optJSONArray("descriptors").objects()
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(descriptors, key = { it.getString("id") }) { descriptor ->
                val id = descriptor.getString("id")
                val value = ModelPresentation.value(descriptor, selection)
                val available = ModelPresentation.available(descriptor, value)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(descriptor.optString("label"), color = colors.dim, style = MaterialTheme.typography.labelMedium)
                    if (!available) Text(stringResource(R.string.providers_unavailable_value, value?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()),
                        style = MaterialTheme.typography.bodySmall, color = colors.dim)
                    when (descriptor.optString("type")) {
                        "boolean" -> Switch(checked = value == true, onCheckedChange = { onOption(id, it) }, enabled = enabled && !loading)
                        "select" -> descriptor.optJSONArray("options").objects().forEach { choice ->
                            Row(Modifier.fillMaxWidth().clickable(enabled && !loading && choice.optBoolean("available", true)) { onOption(id, choice.getString("id")) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = value == choice.optString("id"), onClick = { onOption(id, choice.getString("id")) }, enabled = enabled && !loading && choice.optBoolean("available", true))
                                Text(choice.optString("label"), color = colors.foreground)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Exact provider driver identity from models.catalog; never the model name or instance label. */
@DrawableRes
internal fun providerDrawable(driverKind: String): Int = when (driverKind) {
    "codex" -> R.drawable.ic_provider_openai
    "claudeAgent" -> R.drawable.ic_provider_claude
    "opencode" -> R.drawable.ic_provider_opencode
    "cursor" -> R.drawable.ic_provider_cursor
    else -> R.drawable.ic_provider_acp
}

@Composable
private fun ProviderIcon(provider: JSONObject, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    RegistryIcon(provider.optString("registryIcon"), modifier.size(20.dp)) {
        Icon(painterResource(providerDrawable(provider.optString("driverKind"))), contentDescription = null,
            tint = tint, modifier = modifier.size(20.dp).testTag("provider_icon_${provider.optString("instanceId")}"))
    }
}
