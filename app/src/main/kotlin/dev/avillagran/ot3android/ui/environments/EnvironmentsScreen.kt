package dev.avillagran.ot3android.ui.environments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.common.ConnectionChip

object EnvironmentsScreenTags {
    const val ROOT = "environments_screen"
    const val ADD = "environments_add"
    const val LIST = "environments_list"
    const val EMPTY = "environments_empty"
    fun row(id: String) = "environment_row_$id"
    fun edit(id: String) = "environment_edit_$id"
    fun delete(id: String) = "environment_delete_$id"
    const val CONFIRM_DELETE = "environments_confirm_delete"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentsScreen(
    viewModel: EnvironmentsViewModel,
    onOpen: (environmentId: String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (environmentId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.testTag(EnvironmentsScreenTags.ROOT),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.environments_title)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, modifier = Modifier.testTag(EnvironmentsScreenTags.ADD)) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.environments_add))
            }
        },
    ) { padding ->
        val list = items
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.environments_empty),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(EnvironmentsScreenTags.EMPTY),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(EnvironmentsScreenTags.LIST)) {
                items(list, key = { it.environment.id }) { item ->
                    val id = item.environment.id
                    ListItem(
                        headlineContent = { Text(item.environment.label) },
                        supportingContent = { Text(item.environment.baseUrl) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ConnectionChip(item.state)
                                IconButton(onClick = { onEdit(id) }, modifier = Modifier.testTag(EnvironmentsScreenTags.edit(id))) {
                                    Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.action_edit))
                                }
                                IconButton(onClick = { deleting = id }, modifier = Modifier.testTag(EnvironmentsScreenTags.delete(id))) {
                                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOpen(id) }.testTag(EnvironmentsScreenTags.row(id)),
                    )
                }
            }
        }
    }

    val target = deleting?.let { id -> items?.firstOrNull { it.environment.id == id } }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.environments_delete_title, target.environment.label)) },
            text = { Text(stringResource(R.string.environments_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(target.environment.id)
                        deleting = null
                    },
                    modifier = Modifier.testTag(EnvironmentsScreenTags.CONFIRM_DELETE),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
