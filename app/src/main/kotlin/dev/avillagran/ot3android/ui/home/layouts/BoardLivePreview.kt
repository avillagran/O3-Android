package dev.avillagran.ot3android.ui.home.layouts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.Ot3Application
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.home.HomeRow
import dev.avillagran.ot3android.core.thread.ThreadConnection
import dev.avillagran.ot3android.core.thread.ThreadSession
import dev.avillagran.ot3android.core.thread.ThreadView
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn

/** Usa el mismo lector y las mismas reglas de reconexión que la conversación completa. */
@Composable
internal fun BoardLivePreview(row: HomeRow, active: Boolean, expanded: Boolean, onOpen: () -> Unit) {
    val application = LocalContext.current.applicationContext as? Ot3Application
    if (application == null) {
        BoardLivePreviewContent(ThreadView(row.threadId), expanded, onOpen, {})
        return
    }
    val graph = application.graph
    // Si cambia o se retira la autorización, descarta la proyección anterior de esta tarjeta.
    val authorities = remember(graph, row.environmentId) { graph.environmentRepository.cacheAuthorities(row.environmentId).flowOn(Dispatchers.IO) }
    val authority by authorities.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val session = remember(graph, row.environmentId, row.threadId, authority) {
        ThreadSession(row.threadId, graph.connectionManager.connectionFlow(row.environmentId), scope)
    }
    key(row.environmentId, row.threadId, authority) {
        BoardSessionLifecycle(session, active && authority != null)
        val view by session.view.collectAsStateWithLifecycle()
        BoardLivePreviewContent(view, expanded, onOpen, session::retry)
    }
}

@Composable
internal fun BoardSessionLifecycle(session: ThreadSession, active: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session, active, lifecycle) {
        fun update() { if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) session.start() else session.close() }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); session.close() }
    }
}

@Composable
internal fun BoardLivePreviewContent(view: ThreadView, expanded: Boolean, onOpen: () -> Unit, onRetry: () -> Unit) {
    val messages = remember(view.messages) { view.messages.filter { it.role == "user" || it.role == "assistant" }.takeLast(8) }
    val list = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !autoScrolling) follow = !list.canScrollForward
        }
    }
    val tail = messages.lastOrNull()
    LaunchedEffect(messages.size, tail?.id, tail?.rev, tail?.text) {
        if (follow && !list.isScrollInProgress && messages.isNotEmpty()) {
            autoScrolling = true
            try {
                list.scrollToItem(messages.lastIndex)
                val layout = list.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull { it.index == messages.lastIndex }
                if (last != null) {
                    val overflow = last.offset + last.size - layout.viewportEndOffset + layout.afterContentPadding
                    if (overflow > 0) list.scrollBy(overflow.toFloat())
                }
            } finally { autoScrolling = false }
        }
    }
    val colors = Ot3Theme.colors
    Column(Modifier.fillMaxWidth().testTag("board_live_${view.threadId}")) {
        HorizontalDivider(Modifier.padding(horizontal = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().height(if (expanded) 260.dp else 160.dp).testTag("board_live_messages_${view.threadId}"),
            state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (messages.isEmpty()) item {
                Text(stringResource(when {
                    view.connectionState is ThreadConnection.Failed -> R.string.board_live_unavailable
                    view.connectionState is ThreadConnection.Waiting && view.loaded -> R.string.board_live_waiting
                    view.loaded -> R.string.board_live_empty
                    else -> R.string.board_live_loading
                }), color = colors.dim, style = MaterialTheme.typography.bodySmall)
            }
            items(messages, key = { it.id }) { message ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (message.role == "user") Text(stringResource(R.string.board_live_you), color = colors.dim, style = MaterialTheme.typography.labelSmall)
                    Text(message.text.takeLast(4_000), color = colors.foreground, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (view.connectionState is ThreadConnection.Failed || view.connectionState is ThreadConnection.Retrying)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.ot3_home_refresh)) }
            else if (view.stale) Text(stringResource(R.string.board_live_waiting), color = colors.dim, style = MaterialTheme.typography.labelSmall)
            else Spacer(Modifier.width(1.dp))
            TextButton(onClick = onOpen, modifier = Modifier.testTag("board_live_write_${view.threadId}")) {
                Text(stringResource(R.string.board_live_write))
            }
        }
    }
}
