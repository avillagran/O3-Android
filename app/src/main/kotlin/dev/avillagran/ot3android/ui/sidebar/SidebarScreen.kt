package dev.avillagran.ot3android.ui.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.shell.RowStatus
import dev.avillagran.ot3android.core.shell.SidebarRow
import dev.avillagran.ot3android.ui.common.ConnectionChip
import dev.avillagran.ot3android.ui.common.StatusLabel
import dev.avillagran.ot3android.ui.common.needsEdit

object SidebarScreenTags {
    const val ROOT = "sidebar_screen"
    const val LIST = "sidebar_list"
    const val RETRY = "sidebar_retry"
    const val EDIT = "sidebar_edit"
    const val ACTIVE_HEADER = "sidebar_active_header"
    const val SETTLED_HEADER = "sidebar_settled_header"
    const val EMPTY = "sidebar_empty"
    const val LOADING = "sidebar_loading"
    const val NOT_FOUND = "sidebar_not_found"
    fun project(id: String?) = "sidebar_project_${id.orEmpty()}"
    fun thread(id: String) = "sidebar_thread_$id"
    fun status(id: String) = "sidebar_status_$id"
}

@Composable
fun SidebarScreen(
    viewModel: SidebarViewModel,
    onOpenThread: (threadId: String) -> Unit,
    onEditEnvironment: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SidebarContent(
        state = state,
        onOpenThread = onOpenThread,
        onToggleSettled = viewModel::toggleSettled,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onEditEnvironment = onEditEnvironment,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Stateless sidebar: header with connection state, then the lazy list of sections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidebarContent(
    state: SidebarUiState,
    onOpenThread: (threadId: String) -> Unit,
    onToggleSettled: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onEditEnvironment: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.testTag(SidebarScreenTags.ROOT),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ConnectionChip(state.connection)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.connection.needsEdit()) {
                        TextButton(onClick = onEditEnvironment, modifier = Modifier.testTag(SidebarScreenTags.EDIT)) {
                            val label = if (state.connection == ConnectionState.Unauthorized) R.string.sidebar_edit_token else R.string.action_edit
                            Text(stringResource(label))
                        }
                    }
                    val live = state.connection is ConnectionState.Connected || state.connection == ConnectionState.Connecting
                    if (!live && !state.notFound) {
                        IconButton(onClick = onRetry, modifier = Modifier.testTag(SidebarScreenTags.RETRY)) {
                            Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.action_retry))
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val items = state.items
            when {
                state.notFound -> Centered {
                    Text(
                        stringResource(R.string.sidebar_not_found),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(SidebarScreenTags.NOT_FOUND),
                    )
                }
                items == null -> Centered {
                    if (state.connection == ConnectionState.Connecting || state.connection is ConnectionState.Connected) {
                        CircularProgressIndicator(Modifier.testTag(SidebarScreenTags.LOADING))
                    }
                }
                items.isEmpty() -> Centered {
                    Text(
                        stringResource(R.string.sidebar_empty),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(SidebarScreenTags.EMPTY),
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize().testTag(SidebarScreenTags.LIST)) {
                    items(items, key = { it.key }, contentType = { it::class }) { item ->
                        when (item) {
                            is SidebarItem.ActiveHeader -> SectionHeader(
                                text = stringResource(R.string.sidebar_section_active),
                                modifier = Modifier.testTag(SidebarScreenTags.ACTIVE_HEADER),
                            )
                            is SidebarItem.ProjectHeader -> Text(
                                text = item.title ?: stringResource(R.string.sidebar_no_project),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                                    .testTag(SidebarScreenTags.project(item.projectId)),
                            )
                            is SidebarItem.Thread -> ThreadRow(item.row, onClick = { onOpenThread(item.row.thread.id) })
                            is SidebarItem.SettledHeader -> SettledHeader(item, onToggleSettled)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    // A scrollable child keeps pull to refresh usable on empty and loading states.
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        item { Box(Modifier.padding(32.dp)) { content() } }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettledHeader(item: SidebarItem.SettledHeader, onToggle: () -> Unit) {
    val expansion = stringResource(if (item.expanded) R.string.sidebar_expanded else R.string.sidebar_collapsed)
    Column {
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = expansion }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(SidebarScreenTags.SETTLED_HEADER),
        ) {
            Text(
                stringResource(R.string.sidebar_section_settled, item.count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(painterResource(if (item.expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null)
        }
    }
}

@Composable
private fun ThreadRow(row: SidebarRow, onClick: () -> Unit) {
    val thread = row.thread
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(SidebarScreenTags.thread(thread.id)),
    ) {
        if (row.pinned) {
            Icon(
                painterResource(R.drawable.ic_push_pin),
                contentDescription = stringResource(R.string.sidebar_pinned),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                thread.title ?: stringResource(R.string.sidebar_untitled),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            thread.branch?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StatusChip(row.status, Modifier.testTag(SidebarScreenTags.status(thread.id)))
    }
}

@Composable
private fun StatusChip(status: RowStatus, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    when (status) {
        RowStatus.NeedsYou -> StatusLabel(stringResource(R.string.status_needs_you), colors.tertiaryContainer, colors.onTertiaryContainer, modifier)
        RowStatus.Working -> StatusLabel(stringResource(R.string.status_working), colors.primaryContainer, colors.onPrimaryContainer, modifier)
        RowStatus.Failed -> StatusLabel(stringResource(R.string.status_failed), colors.errorContainer, colors.onErrorContainer, modifier)
        RowStatus.Quiet -> Unit
    }
}
