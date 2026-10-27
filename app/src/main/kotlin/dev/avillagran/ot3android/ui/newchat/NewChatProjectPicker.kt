package dev.avillagran.ot3android.ui.newchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.icons.Ot3Icons

/** One project offered when starting a new chat, with the environment it belongs to. */
data class NewChatProjectOption(
    val environmentId: String,
    val environmentLabel: String,
    val projectId: String,
    val projectTitle: String,
    val workspaceRoot: String?,
    val connected: Boolean,
    val writable: Boolean = true,
)

object NewChatProjectPickerTags {
    const val LIST = "new_chat_projects"
    const val SEARCH = "new_chat_project_search"
    fun row(environmentId: String, projectId: String) = "new_chat_project_$environmentId/$projectId"
}

/**
 * Project picker for a new chat. Every row names both its project and its environment, since the
 * same project can exist in more than one environment. Offline environments stay visible but their
 * rows are not selectable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatProjectPicker(
    projects: List<NewChatProjectOption>,
    loading: Boolean,
    onSelect: (NewChatProjectOption) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(projects, query) {
        val needle = query.trim()
        if (needle.isEmpty()) projects else projects.filter { project ->
            project.projectTitle.contains(needle, ignoreCase = true) ||
                project.workspaceRoot.orEmpty().contains(needle, ignoreCase = true) ||
                project.environmentLabel.contains(needle, ignoreCase = true) ||
                project.environmentId.contains(needle, ignoreCase = true) ||
                project.projectId.contains(needle, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ot3_home_new_thread)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(NewChatProjectPickerTags.SEARCH),
                singleLine = true,
                leadingIcon = { Icon(painterResource(Ot3Icons.Search), contentDescription = null) },
                placeholder = { Text(stringResource(R.string.ot3_filter_project)) },
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(NewChatProjectPickerTags.LIST),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    loading -> item {
                        Box(
                            Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.ot3_loading))
                            }
                        }
                    }
                    visible.isEmpty() -> item {
                        Box(
                            Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(stringResource(R.string.ot3_new_chat_no_projects))
                                TextButton(onClick = onRetry) {
                                    Text(stringResource(R.string.ot3_dialog_retry))
                                }
                            }
                        }
                    }
                    else -> items(visible, key = { "${it.environmentId}/${it.projectId}" }) { project ->
                        val rowModifier = Modifier
                            .clickable(enabled = project.connected && project.writable) { onSelect(project) }
                            .alpha(if (project.connected && project.writable) 1f else 0.5f)
                            .testTag(NewChatProjectPickerTags.row(project.environmentId, project.projectId))
                        ListItem(
                            headlineContent = { Text(project.projectTitle) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(project.environmentLabel)
                                    project.workspaceRoot?.let { Text(it) }
                                    if (!project.connected) Text(stringResource(R.string.ot3_connection_offline))
                                    else if (!project.writable) Text(stringResource(R.string.thread_notice_rejected_plain))
                                }
                            },
                            modifier = rowModifier,
                        )
                    }
                }
            }
        }
    }
}
