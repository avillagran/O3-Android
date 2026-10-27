package dev.avillagran.ot3android.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.home.HomeRow
import dev.avillagran.ot3android.core.home.ThreadAction

@Composable
fun ThreadManagementDialog(row: HomeRow, action: ThreadAction, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var title by rememberSaveable(row.key, action) { mutableStateOf(row.title.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(threadActionText(action, row)) },
        text = { Column {
            if (action == ThreadAction.Rename) OutlinedTextField(title, { title = it }, singleLine = true,
                modifier = Modifier.testTag("thread_rename_title"))
            else Text(row.title ?: row.threadId)
            if (action == ThreadAction.Delete) {
                Text(stringResource(R.string.ot3_dialog_delete_thread_body))
                Text(stringResource(R.string.ot3_dialog_cannot_undo))
            }
        } },
        confirmButton = { TextButton(onClick = { onConfirm(title) }, enabled = action != ThreadAction.Rename || title.isNotBlank(),
            modifier = Modifier.testTag("thread_action_confirm")) { Text(if (action == ThreadAction.Rename)
                stringResource(R.string.ot3_dialog_save) else threadActionText(action, row)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ot3_dialog_cancel)) } })
}
