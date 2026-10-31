package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.Ot3Sheet
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject

/** Presentation names follow T3PermissionPicker.qml. Only native-advertised choices are offered. */
@Composable
internal fun ComposerPermissions(
    catalogJson: String?, selectionJson: String?, mode: String?, enabled: Boolean,
    onRefresh: () -> Unit, onSelect: (String) -> Unit,
) {
    val catalog = remember(catalogJson) { catalogJson?.let(::JSONObject) }
    val instance = remember(selectionJson) { selectionJson?.let(::JSONObject)?.optString("instanceId") }
    val provider = catalog?.optJSONArray("providers").objects().firstOrNull { it.optString("instanceId") == instance }
    val advertised = provider?.optJSONArray("supportedRuntimeModes")
    val modes = (0 until (advertised?.length() ?: 0)).mapNotNull { advertised?.optString(it)?.takeIf(String::isNotBlank) }
    val definitions = mapOf(
        "approval-required" to (R.string.ot3_permission_supervised to R.string.ot3_permission_supervised_detail),
        "auto-accept-edits" to (R.string.ot3_permission_accept_edits to R.string.ot3_permission_accept_edits_detail),
        "auto" to (R.string.ot3_permission_auto to R.string.ot3_permission_auto_detail),
        "full-access" to (R.string.ot3_permission_full_access to R.string.ot3_permission_full_access_detail),
    )
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.ot3_permission_permissions) + definitions[mode]?.let { ": " + stringResource(it.first) }.orEmpty()
    val icon = when (mode) { "auto-accept-edits" -> "pen-line"; "auto" -> "sparkles"; "full-access" -> "lock-open"; else -> "lock" }
    Ot3IconButton(icon, label, { onRefresh(); open = true }, enabled = enabled,
        modifier = Modifier.testTag("composer_permissions"))
    if (open) Ot3Sheet(onDismissRequest = { open = false }, title = stringResource(R.string.ot3_permission_permissions)) {
        if (modes.isEmpty()) Text(stringResource(R.string.providers_modes_unavailable), Modifier.padding(16.dp), color = Ot3Theme.colors.dim)
        modes.forEach { choice ->
            val definition = definitions[choice]
            Row(Modifier.fillMaxWidth().testTag("permission_$choice").clickable(enabled) {
                onSelect(choice); open = false
            }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == choice, onClick = { onSelect(choice); open = false }, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(definition?.let { stringResource(it.first) } ?: choice, color = Ot3Theme.colors.foreground)
                    definition?.let { Text(stringResource(it.second), color = Ot3Theme.colors.dim, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
