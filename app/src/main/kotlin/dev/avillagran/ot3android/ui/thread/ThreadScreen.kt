package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.SolidColor
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.kit.Ot3Spinner
import dev.avillagran.ot3android.ui.kit.rememberKitClock
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.navigation.LocalThreadSidebarControl
import dev.avillagran.ot3android.core.thread.PendingCommand
import dev.avillagran.ot3android.core.thread.RuntimeRequest
import dev.avillagran.ot3android.core.thread.ThreadConnection
import dev.avillagran.ot3android.core.thread.ThreadMessage
import dev.avillagran.ot3android.ui.common.ConnectionChip
import dev.avillagran.ot3android.ui.common.StatusLabel
import dev.avillagran.ot3android.ui.common.errorText

object ThreadScreenTags {
    const val ROOT = "thread_screen"
    const val TITLE = "thread_title"
    const val TIMELINE = "thread_timeline"
    const val LOADING = "thread_loading"
    const val EMPTY = "thread_empty"
    const val WORKING = "thread_working"
    const val INTERRUPT = "thread_interrupt"
    const val STALE = "thread_stale"
    const val STREAM_STATE = "thread_stream_state"
    const val STREAM_RETRY = "thread_stream_retry"
    const val COMPOSER = "thread_composer"
    const val SEND = "thread_send"
    const val SENDING = "thread_sending"
    fun message(id: String) = "thread_message_$id"
    fun streaming(id: String) = "thread_streaming_$id"
    fun request(id: String) = "thread_request_$id"
    fun unconfirmed(id: String) = "thread_unconfirmed_$id"
    fun resend(id: String) = "thread_resend_$id"
}

@Composable
fun ThreadScreen(viewModel: ThreadViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ThreadContent(
        state = state,
        onDraftChange = viewModel::onDraftChange,
        onSend = viewModel::send,
        onInterrupt = viewModel::interrupt,
        onResend = viewModel::retry,
        onDiscard = viewModel::discard,
        onRetryStream = viewModel::retryStream,
        onDismissNotice = viewModel::dismissNotice,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Stateless thread screen: top bar, banners, the timeline (newest at the bottom) and the composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadContent(
    state: ThreadUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onResend: (commandId: String) -> Unit,
    onDiscard: (commandId: String) -> Unit,
    onRetryStream: () -> Unit,
    onDismissNotice: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val notice = state.notice
    val noticeText = notice?.let { noticeText(it) }
    val resendLabel = stringResource(R.string.thread_resend)
    LaunchedEffect(notice) {
        if (notice == null || noticeText == null) return@LaunchedEffect
        val unconfirmed = notice as? ThreadNotice.Unconfirmed
        val result = snackbar.showSnackbar(
            message = noticeText,
            actionLabel = if (unconfirmed != null) resendLabel else null,
            withDismissAction = true,
            duration = if (unconfirmed != null) SnackbarDuration.Indefinite else SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed && unconfirmed != null) onResend(unconfirmed.commandId)
        onDismissNotice()
    }

    Scaffold(
        modifier = modifier.testTag(ThreadScreenTags.ROOT),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ot3Theme.colors.background),
                title = {
                    Column {
                        Text(
                            state.title ?: stringResource(R.string.sidebar_untitled),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(ThreadScreenTags.TITLE),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConnectionChip(state.connection)
                            if (state.view.working) {
                                Spacer(Modifier.width(8.dp))
                                WorkingLabel()
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
                actions = {
                    LocalThreadSidebarControl.current?.invoke()
                    if (state.view.working) {
                        IconButton(
                            onClick = onInterrupt,
                            enabled = state.canInterrupt,
                            modifier = Modifier.testTag(ThreadScreenTags.INTERRUPT),
                        ) {
                            Icon(painterResource(R.drawable.ic_stop), stringResource(R.string.thread_interrupt))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                draft = state.draft,
                canSend = state.canSend,
                sending = state.sending,
                onDraftChange = onDraftChange,
                onSend = onSend,
                modifier = Modifier.navigationBarsPadding().imePadding(),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            StreamState(state.view.connectionState, state.view.stale, onRetryStream)
            state.view.pendingRequests.forEach { PendingRequestBanner(it) }
            state.unconfirmed.forEach { UnconfirmedRow(it, state.connected, onResend, onDiscard) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.view.loaded -> Ot3Spinner(
                        Modifier.align(Alignment.Center).testTag(ThreadScreenTags.LOADING), size = 24.dp,
                    )
                    state.view.messages.isEmpty() -> Text(
                        stringResource(R.string.thread_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center).testTag(ThreadScreenTags.EMPTY),
                    )
                    else -> Timeline(state.view.messages)
                }
            }
        }
    }
}

@Composable
private fun Timeline(messages: List<ThreadMessage>) {
    // Reverse layout keeps the newest message at the bottom and the list anchored there.
    val newestFirst = remember(messages) { messages.asReversed() }
    LazyColumn(
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.fillMaxSize().testTag(ThreadScreenTags.TIMELINE),
    ) {
        items(newestFirst, key = { it.id }) { message -> MessageBubble(message) }
    }
}

@Composable
private fun MessageBubble(message: ThreadMessage) {
    val user = message.role == "user"
    val colors = Ot3Theme.colors
    val typing = stringResource(R.string.thread_message_typing)
    Row(
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
        modifier = Modifier.fillMaxWidth().testTag(ThreadScreenTags.message(message.id))
            .semantics { if (message.streaming) stateDescription = typing },
    ) {
        Surface(
            color = if (user) colors.surface else colors.background,
            contentColor = colors.foreground,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                SelectionContainer {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                }
                // Decoration only: the bubble's state description says it is still being written.
                if (message.streaming) {
                    StreamingCursor(Modifier.clearAndSetSemantics { testTag = ThreadScreenTags.streaming(message.id) })
                }
            }
        }
    }
}

@Composable
private fun StreamingCursor(modifier: Modifier = Modifier) {
    if (!Ot3Theme.motion.animates) {
        Text("▍", style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        return
    }
    val clock by rememberKitClock(true)
    val phase = clock % 1f
    val alpha = 1f - 0.8f * (if (phase < 0.5f) phase * 2f else (1f - phase) * 2f)
    Text("▍", style = MaterialTheme.typography.bodyLarge, modifier = modifier.alpha(alpha))
}

@Composable
private fun WorkingLabel() {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(ThreadScreenTags.WORKING)) {
        Ot3Spinner(size = 12.dp, tint = Ot3Theme.colors.working)
        Spacer(Modifier.width(4.dp))
        StatusLabel(stringResource(R.string.status_working), colors.tertiaryContainer, colors.onTertiaryContainer)
    }
}

@Composable
private fun StreamState(connection: ThreadConnection, stale: Boolean, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val text = when (connection) {
        // The handle's stream slots are taken (other open threads, or the sidebar): say so.
        is ThreadConnection.Retrying -> if (connection.error == "subscription_limit") {
            stringResource(R.string.thread_stream_subscription_limit)
        } else {
            stringResource(R.string.thread_stream_retrying, errorText(connection.error))
        }
        is ThreadConnection.Failed -> stringResource(R.string.thread_stream_failed, errorText(connection.error))
        else -> null
    }
    if (text == null && !stale) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        if (stale) {
            StatusLabel(
                stringResource(R.string.thread_stale),
                colors.secondaryContainer,
                colors.onSecondaryContainer,
                Modifier.testTag(ThreadScreenTags.STALE),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (text != null) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).testTag(ThreadScreenTags.STREAM_STATE),
            )
            TextButton(onClick = onRetry, modifier = Modifier.testTag(ThreadScreenTags.STREAM_RETRY)) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun PendingRequestBanner(request: RuntimeRequest) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().testTag(ThreadScreenTags.request(request.id)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val title = if (request.kind == "user_input") {
                stringResource(R.string.thread_request_answer)
            } else {
                stringResource(R.string.thread_request_approval, request.kind ?: "?")
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            request.prompt?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            Text(stringResource(R.string.thread_request_elsewhere), style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider()
}

@Composable
private fun UnconfirmedRow(
    command: PendingCommand,
    connected: Boolean,
    onResend: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag(ThreadScreenTags.unconfirmed(command.commandId)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Text(
                command.text?.let { stringResource(R.string.thread_unconfirmed, it) }
                    ?: stringResource(R.string.thread_unconfirmed_plain),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onResend(command.commandId) },
                enabled = connected,
                modifier = Modifier.testTag(ThreadScreenTags.resend(command.commandId)),
            ) { Text(stringResource(R.string.thread_resend)) }
            TextButton(onClick = { onDiscard(command.commandId) }) { Text(stringResource(R.string.thread_discard)) }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    canSend: Boolean,
    sending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ot3Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(if (focused) colors.composerFocus else colors.composerBorder,
        Ot3Theme.motion.colorChange(), label = "composerBorder")
    Surface(color = colors.background, modifier = modifier.fillMaxWidth().testTag("thread_composer_surface")) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                .border(1.dp, border, RoundedCornerShape(10.dp))
                .background(colors.surface, RoundedCornerShape(10.dp)).padding(4.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = !sending,
                interactionSource = interaction,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.foreground),
                cursorBrush = SolidColor(colors.accent),
                maxLines = 6,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(ThreadScreenTags.COMPOSER),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                        if (draft.isEmpty()) Text(stringResource(R.string.thread_composer_hint),
                            style = MaterialTheme.typography.bodyMedium, color = colors.dim)
                        inner()
                    }
                },
            )
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (sending) Ot3Spinner(Modifier.testTag(ThreadScreenTags.SENDING), size = 24.dp)
                else Ot3IconButton("arrow-up", stringResource(R.string.thread_send), onSend,
                    modifier = Modifier.testTag(ThreadScreenTags.SEND), enabled = canSend, tint = colors.accent)
            }
        }
    }
}

/** The longest rejection reason a snackbar shows; the server's text can be a whole schema error. */
private const val REASON_MAX = 200

private fun String.truncated(max: Int): String = if (length <= max) this else take(max - 1).trimEnd() + "…"

/** Snackbar text for a notice; codes come from the lib and never carry credentials. */
@Composable
private fun noticeText(notice: ThreadNotice): String = when (notice) {
    is ThreadNotice.Unconfirmed -> stringResource(R.string.thread_notice_unknown)
    is ThreadNotice.Failed -> when (notice.code) {
        "rpc_outcome_unknown" -> stringResource(R.string.thread_notice_unknown)
        "rpc_rejected" -> notice.reason?.let { stringResource(R.string.thread_notice_rejected, it.truncated(REASON_MAX)) }
            ?: stringResource(R.string.thread_notice_rejected_plain)
        "write_not_authorized" -> stringResource(R.string.thread_notice_scope, notice.requiredScope ?: "orchestration:operate")
        "not_connected" -> stringResource(R.string.thread_notice_not_connected)
        else -> errorText(notice.code)
    }
}
