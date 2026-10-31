package dev.avillagran.ot3android.ui.settings.projects

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R

/** Extra local actions, wired by P20 without changing P04's existing row callbacks. */
@Composable
fun ProjectGroupingContent(selected: ProjectGrouping, enabled: Boolean, onSelect: (ProjectGrouping) -> Unit) {
    Column {
        ProjectGrouping.entries.forEach { mode ->
            TextButton(onClick = { onSelect(mode) }, enabled = enabled && selected != mode) {
                Text(stringResource(when (mode) {
                    ProjectGrouping.Inherit -> R.string.thread_menus_inherit
                    ProjectGrouping.Repository -> R.string.thread_menus_repository
                    ProjectGrouping.RepositoryPath -> R.string.thread_menus_repository_path
                    ProjectGrouping.Separate -> R.string.thread_menus_separate
                }))
            }
        }
    }
}

@Composable
fun NamedGroupsContent(names: List<String>, selected: String?, enabled: Boolean, onSelect: (String?) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Column {
        Text(stringResource(R.string.thread_menus_groups), style = MaterialTheme.typography.titleSmall)
        names.forEach { name -> TextButton(onClick = { onSelect(name) }, enabled = enabled && name != selected) { Text(name) } }
        OutlinedTextField(draft, { draft = it }, enabled = enabled,
            label = { Text(stringResource(R.string.thread_menus_group_name)) }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { onSelect(draft.trim()); draft = "" }, enabled = enabled && draft.trim().isNotEmpty() &&
            draft.trim().codePointCount(0, draft.trim().length) <= 48 && draft.none { it.isISOControl() }) {
            Text(stringResource(R.string.ot3_dialog_save))
        }
        TextButton(onClick = { onSelect(null) }, enabled = enabled && selected != null) { Text(stringResource(R.string.thread_menus_remove_group)) }
    }
}

/** Keep the unknown receipt visible after closing/reopening the originating sheet. */
@Composable
fun ThreadMenuRecoveryContent(state: ThreadMenuOperationState, onRetry: () -> Unit) {
    if (state.retryCommandId != null) {
        Column {
            Text(stringResource(R.string.thread_menus_unknown))
            TextButton(onClick = onRetry, enabled = !state.busy) { Text(stringResource(R.string.thread_menus_retry)) }
        }
    }
}
