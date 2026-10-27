package dev.avillagran.ot3android.ui.newchat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.thread.ComposerModels
import dev.avillagran.ot3android.ui.thread.ComposerPermissions
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewChatDraftScreen(state: NewChatDraftState, onBack: () -> Unit,
    onText: (String) -> Unit, onTitle: (String) -> Unit, onSelect: (JSONObject) -> Unit,
    onOption: (String, Any) -> Unit, onRuntime: (String) -> Unit, onRefresh: () -> Unit, onCreate: (String) -> Unit,
    environmentLabel: String = "", onDiscard: () -> Unit = {},
) {
    var confirmingDiscard by remember { mutableStateOf(false) }
    if (confirmingDiscard) AlertDialog(onDismissRequest = { confirmingDiscard = false },
        text = { Text(stringResource(R.string.thread_notice_unknown)) },
        confirmButton = { TextButton(onClick = { confirmingDiscard = false; onDiscard() },
            modifier = Modifier.testTag("new_chat_confirm_discard")) { Text(stringResource(R.string.thread_discard)) } },
        dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(R.string.ot3_dialog_cancel)) } })
    val fallback = stringResource(R.string.ot3_row_untitled)
    val catalog = remember(state.catalog) { state.catalog?.let { JSONObject(it).put("favoritesReady", false).toString() } }
    val selection = remember(state.selection) { state.selection?.let(::JSONObject) }
    val selectedModelAvailable = remember(state.catalog, state.selection) {
        val models = state.catalog?.let(::JSONObject)?.optJSONArray("models")
        models != null && selection != null && (0 until models.length()).any { index ->
            val row = models.getJSONObject(index)
            row.optBoolean("available") && row.optString("instanceId") == selection.optString("instanceId") &&
                row.optString("model") == selection.optString("model")
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.ot3_home_new_thread)) },
        navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.ot3_dialog_cancel)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .padding(horizontal = 12.dp).testTag("new_chat_draft"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.projectTitle, style = MaterialTheme.typography.titleMedium)
            if (environmentLabel.isNotBlank()) Text(environmentLabel, style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.ot3_new_chat_current_checkout), style = MaterialTheme.typography.labelSmall)
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error) Text(stringResource(R.string.home_action_unconfirmed), color = MaterialTheme.colorScheme.error)
            if (!state.writable && !state.loading) Text(stringResource(R.string.thread_notice_rejected_plain))
            OutlinedTextField(state.title, onTitle, label = { Text(stringResource(R.string.ot3_new_chat_title)) },
                enabled = !state.busy && state.pending == null, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("new_chat_title"))
            OutlinedTextField(state.text, onText, label = { Text(stringResource(R.string.thread_composer_hint)) },
                enabled = !state.busy && state.pending == null,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("new_chat_message"))
            ComposerModels(catalog, state.selection, state.loading, !state.busy && state.pending == null,
                onRefresh, { row -> onSelect(JSONObject().put("instanceId", row.getString("instanceId"))
                    .put("model", row.getString("model")).put("options", org.json.JSONArray())) }, onOption, { _, _ -> }) {
                ComposerPermissions(catalog, state.selection, state.runtimeMode, !state.busy && state.pending == null,
                    onRefresh, onRuntime)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (state.pending != null) TextButton(onClick = { confirmingDiscard = true }, enabled = !state.busy,
                    modifier = Modifier.testTag("new_chat_discard")) { Text(stringResource(R.string.thread_discard)) }
                if (state.error && state.pending == null) TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.ot3_dialog_retry)) }
                Button(onClick = { onCreate(fallback) }, enabled = !state.busy && !state.loading && state.writable &&
                    (state.pending != null || (state.text.isNotBlank() && selectedModelAvailable)),
                    modifier = Modifier.testTag("new_chat_send")) {
                    Text(stringResource(if (state.pending != null) R.string.thread_resend else R.string.thread_send))
                }
            }
        }
    }
}
