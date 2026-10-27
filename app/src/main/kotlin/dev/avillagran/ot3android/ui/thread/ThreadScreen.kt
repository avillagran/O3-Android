package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
fun ThreadScreen(viewModel: ThreadViewModel, onBack: () -> Unit, modifier: Modifier = Modifier, quotaStrip: @Composable () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val imageConnection by viewModel.imageConnection.collectAsStateWithLifecycle()
    androidx.compose.runtime.CompositionLocalProvider(LocalReaderImages provides ReaderImages(imageConnection, { viewModel.isImageConnectionCurrent(imageConnection) }, viewModel::readImage)) {
        ThreadContent(
            state = state,
            editorIdentity = viewModel.editorIdentity,
            quotaStrip = quotaStrip,
            onDraftChange = viewModel::onDraftChange,
            onDraftChangeTagged = { text, token -> viewModel.onDraftChange(text, token) },
            onSend = viewModel::send,
            onInterrupt = viewModel::interrupt,
            onResend = viewModel::retry,
            onDiscard = viewModel::discard,
            onRetryStream = viewModel::retryStream,
            onDismissNotice = viewModel::dismissNotice,
            onBack = onBack,
            modifier = modifier,
            onInspect = viewModel::inspect,
            onReadDiff = viewModel::readDiff,
            onQueueCommand = viewModel::queueCommand,
            onEditQueued = viewModel::editQueued,
            onCancelQueueEdit = viewModel::cancelQueueEdit,
            onRefreshModels = viewModel::refreshModels,
            onSelectModel = viewModel::selectModel,
            onSelectModelOption = viewModel::selectModelOption,
            onFavoriteModel = viewModel::favoriteModel,
            onApprovalDecision = viewModel::respondApproval,
            onQuestionResponse = viewModel::respondQuestion,
            onRuntimeMode = viewModel::selectRuntimeMode,
        )
    }
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
    onInspect: suspend (org.json.JSONObject) -> org.json.JSONObject? = { null },
    onReadDiff: suspend (org.json.JSONObject, Boolean) -> org.json.JSONObject? = { _, _ -> null },
    onQueueCommand: (org.json.JSONObject) -> Unit = {},
    onEditQueued: (org.json.JSONObject) -> Unit = {},
    onCancelQueueEdit: () -> Unit = {},
    onRefreshModels: () -> Unit = {},
    onSelectModel: (org.json.JSONObject) -> Unit = {},
    onSelectModelOption: (String, Any) -> Unit = { _, _ -> },
    onFavoriteModel: (org.json.JSONObject, Boolean) -> Unit = { _, _ -> },
    onApprovalDecision: (String, String) -> Unit = { _, _ -> },
    onQuestionResponse: (String, String, org.json.JSONObject) -> Unit = { _, _, _ -> },
    onRuntimeMode: (String) -> Unit = {},
    quotaStrip: @Composable () -> Unit = {},
    editorIdentity: String = state.view.threadId,
    onDraftChangeTagged: ((String, String) -> Unit)? = null,
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

    var expanded by androidx.compose.runtime.saveable.rememberSaveable(editorIdentity) { mutableStateOf(false) }
    val editKey = state.queueEdit?.let { org.json.JSONObject(it).getString("runId") }
    val editors = androidx.compose.runtime.saveable.rememberSaveable(editorIdentity, saver = ComposerEditors.StateSaver) { ComposerEditors() }
    val editor = editors.editor(editKey, state.draft)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(state.draft, state.draftToken, editor) { editor.synchronize(state.draft, state.draftToken) }
    val hasQuestion = (state.view.timeline?.optJSONArray("questions")?.length() ?: 0) > 0
    LaunchedEffect(hasQuestion) { if (hasQuestion) expanded = false }
    fun toggleEditor() {
        editor.newSurface()
        if (expanded) { editor.releaseFocus(); keyboard?.hide(); focus.clearFocus() }
        else editor.requestFocus = true
        expanded = !expanded
    }
    val edit: (String, String) -> Unit = { text, token -> if (onDraftChangeTagged != null) onDraftChangeTagged(text, token) else onDraftChange(text) }
    fun submit() { if (state.canSend && !state.sending) { editor.submitted(); onSend() } }
    androidx.activity.compose.BackHandler(enabled = expanded) { toggleEditor() }
    val timelineState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val toolbar: @Composable () -> Unit = {
        ComposerModels(state.modelCatalog, state.modelSelection, state.modelsLoading,
            state.connected && !state.sending && state.queueEdit == null,
            onRefreshModels, onSelectModel, onSelectModelOption, onFavoriteModel) {
            ComposerPermissions(state.modelCatalog, state.modelSelection, state.view.runtimeMode,
                state.connected && !state.view.stale && !state.sending && state.queueEdit == null && state.unconfirmed.isEmpty(), onRefreshModels, onRuntimeMode)
        }
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
                            if (expanded) stringResource(R.string.ot3_composer_edit) else state.title ?: stringResource(R.string.sidebar_untitled),
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
                    IconButton(onClick = { if (expanded) toggleEditor() else onBack() }, modifier = Modifier.testTag("composer_back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(if (expanded) R.string.ot3_composer_back else R.string.action_back))
                    }
                },
                actions = {
                    if (expanded) Ot3IconButton("arrow-up", stringResource(R.string.thread_send), ::submit, modifier = Modifier.testTag(ThreadScreenTags.SEND), enabled = state.canSend && !state.sending, tint = Ot3Theme.colors.accent)
                    var quotaMenu by remember { mutableStateOf(false) }
                    Box {
                        Ot3IconButton("ellipsis-vertical", stringResource(R.string.home_more), { quotaMenu = true }, modifier = Modifier.testTag("thread_overflow"))
                        androidx.compose.material3.DropdownMenu(expanded = quotaMenu, onDismissRequest = { quotaMenu = false }) {
                            dev.avillagran.ot3android.ui.usage.QuotaToggleMenuItem { quotaMenu = false }
                        }
                    }

                    if (state.view.working) {
                        IconButton(
                            onClick = onInterrupt,
                            enabled = state.canInterrupt,
                            modifier = Modifier.testTag(ThreadScreenTags.INTERRUPT),
                        ) {
                            Icon(painterResource(R.drawable.ic_stop), stringResource(R.string.thread_interrupt))
                        }
                    }
                    if (dev.avillagran.ot3android.ui.navigation.LocalWorkspaceSidebarAction.current) {
                        Spacer(Modifier.width(48.dp))
                    }
                },
            )
        },
        bottomBar = {
            if (!expanded) Column(Modifier.navigationBarsPadding().imePadding()) {
                ThreadQueue(
                    state.view.timeline?.optJSONObject("queue"),
                    state.connected && !state.view.stale && !state.sending && state.unconfirmed.isEmpty(),
                    onQueueCommand,
                    onEditQueued,
                    editingRunId = state.queueEdit?.let { org.json.JSONObject(it).optString("runId") },
                    dragOwner = state.view.threadId to state.queueConnection,
                    feedback = {
                        state.notice?.let { Text(noticeText(it), Modifier.padding(horizontal = 16.dp), color = Ot3Theme.colors.failed) }
                        state.unconfirmed.forEach { UnconfirmedRow(it, state.connected, onResend, onDiscard) }
                    },
                )
                if (state.queueEdit != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.queue_editing), Modifier.weight(1f), color = Ot3Theme.colors.dim)
                        TextButton(onClick = onCancelQueueEdit, enabled = !state.queueBusy) {
                            Text(stringResource(R.string.queue_cancel_edit))
                        }
                    }
                }
                ThreadTasks(state.view.timeline?.optJSONObject("tasks"))
                ApprovalCard(state.view.timeline?.optJSONArray("approvals"),
                    state.connected && !state.view.stale && !state.sending && state.unconfirmed.isEmpty(),
                    state.respondingRequest, state.answeredRequests, onApprovalDecision)
                QuestionCard(state.view.timeline?.optJSONArray("questions"),
                    state.connected && !state.view.stale && !state.sending && state.unconfirmed.isEmpty(),
                    state.respondingRequest, state.answeredRequests, onResponse = onQuestionResponse)
                if (!hasQuestion) ComposerEditor(editor, false, state.canSend, state.sending, edit, ::submit, ::toggleEditor, toolbar = toolbar)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).then(if (expanded) Modifier.imePadding() else Modifier)) {
            if (expanded) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val feedbackHeight = (maxHeight * .25f).coerceAtMost(120.dp)
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().heightIn(max = feedbackHeight).then(Modifier.verticalScroll(rememberScrollState()))) {
                        StreamState(state.view.connectionState, state.view.stale, onRetryStream)
                        state.unconfirmed.forEach { UnconfirmedRow(it, state.connected, onResend, onDiscard) }
                        if (state.queueEdit != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.queue_editing), Modifier.weight(1f), color = Ot3Theme.colors.dim)
                            TextButton(onClick = onCancelQueueEdit, enabled = !state.queueBusy) { Text(stringResource(R.string.queue_cancel_edit)) }
                        }
                        state.notice?.let { if (it is ThreadNotice.Failed) Text(noticeText(it), color = Ot3Theme.colors.failed, modifier = Modifier.padding(horizontal = 12.dp)) }
                        ApprovalCard(state.view.timeline?.optJSONArray("approvals"), state.connected && !state.view.stale && !state.sending && state.unconfirmed.isEmpty(), state.respondingRequest, state.answeredRequests, onApprovalDecision)
                    }
                    ComposerEditor(editor, true, state.canSend, state.sending, edit, ::submit, ::toggleEditor,
                        Modifier.weight(1f).testTag("composer_expanded"), toolbar = toolbar)
                }
            } else {
                quotaStrip()
                StreamState(state.view.connectionState, state.view.stale, onRetryStream)
                state.view.pendingRequests.filter { state.view.timeline?.has(if (it.kind == "user_input") "questions" else "approvals") != true }
                    .forEach { PendingRequestBanner(it) }
                state.unconfirmed.forEach { UnconfirmedRow(it, state.connected, onResend, onDiscard) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !state.view.loaded -> Ot3Spinner(Modifier.align(Alignment.Center).testTag(ThreadScreenTags.LOADING), size = 24.dp)
                        (state.view.timeline?.optJSONArray("timeline")?.length() ?: 0) == 0 && state.view.messages.isEmpty() -> Text(stringResource(R.string.thread_empty), modifier = Modifier.align(Alignment.Center).testTag(ThreadScreenTags.EMPTY))
                        else -> timelineState.SaveableStateProvider(editorIdentity) { TimelineReader(state.view.timeline, state.view.messages, onInspect, onReadDiff) }
                    }
                }
            }
        }
    }
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
        "command_pending" -> stringResource(R.string.queue_command_pending)
        else -> errorText(notice.code)
    }
}
