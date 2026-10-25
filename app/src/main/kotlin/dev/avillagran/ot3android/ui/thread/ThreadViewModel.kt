package dev.avillagran.ot3android.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import dev.avillagran.ot3android.core.shell.ShellThread
import dev.avillagran.ot3android.core.thread.DraftRepository
import dev.avillagran.ot3android.core.thread.MessageSender
import dev.avillagran.ot3android.core.thread.OutboxRepository
import dev.avillagran.ot3android.core.thread.PendingCommand
import dev.avillagran.ot3android.core.thread.SendResult
import dev.avillagran.ot3android.core.thread.ThreadSession
import dev.avillagran.ot3android.core.thread.ThreadView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A message for the snackbar. */
sealed interface ThreadNotice {
    /** The server did not confirm [commandId]; offer to resend it. */
    data class Unconfirmed(val commandId: String) : ThreadNotice

    /** A command failed with the lib's [code]. */
    data class Failed(val code: String, val reason: String? = null, val requiredScope: String? = null) : ThreadNotice
}

data class ThreadUiState(
    val view: ThreadView,
    /** The projection's title, else the shell row's. */
    val title: String? = null,
    /** The thread's `modelSelection` (compact JSON) from the projection, else the shell row's. */
    val modelSelection: String? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val draft: String = "",
    val sending: Boolean = false,
    val interrupting: Boolean = false,
    /** Commands still in the outbox for this thread. */
    val unconfirmed: List<PendingCommand> = emptyList(),
    val notice: ThreadNotice? = null,
) {
    val connected: Boolean get() = connection is ConnectionState.Connected

    val canSend: Boolean get() = draft.isNotBlank() && connected && !sending

    val canInterrupt: Boolean get() = view.working && view.activeRunId != null && connected && !interrupting
}

/** The composer's local state; the rest comes from the session, the supervisor and the outbox. */
private data class Local(
    val draft: String = "",
    val sending: Boolean = false,
    val interrupting: Boolean = false,
    val notice: ThreadNotice? = null,
)

/**
 * One open thread: a [ThreadSession] on the environment's supervisor, the composer with its
 * persisted draft, and sends through [MessageSender]. Sends and their bookkeeping run in
 * [appScope], so leaving the screen does not lose an accepted message's draft clearing.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ThreadViewModel(
    private val environmentId: String,
    private val threadId: String,
    connections: ConnectionManager,
    private val drafts: DraftRepository,
    outbox: OutboxRepository,
    private val sender: MessageSender,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val supervisor = MutableStateFlow<ConnectionSupervisor?>(null)
    private val local = MutableStateFlow(Local())
    @Volatile private var draftLoaded = false
    @Volatile private var savedDraft = ""

    private val session = ThreadSession(
        threadId,
        supervisor.flatMapLatest { it?.connection ?: flowOf<Connection?>(null) },
        viewModelScope,
    )

    private val connection: Flow<ConnectionState> =
        supervisor.flatMapLatest { it?.state ?: flowOf(ConnectionState.Disconnected) }

    /** This thread's sidebar row: the title before the snapshot, and the fallback model selection. */
    private val shellRow: Flow<ShellThread?> = supervisor
        .flatMapLatest { it?.shell ?: flowOf(null) }
        .map { shell -> shell?.thread(threadId) }
        .distinctUntilChanged()

    val state: StateFlow<ThreadUiState> = combine(
        session.view,
        connection,
        shellRow,
        local,
        outbox.observeThread(environmentId, threadId),
    ) { view, connection, shellRow, local, unconfirmed ->
        ThreadUiState(
            view = view,
            title = view.title ?: shellRow?.title,
            modelSelection = view.modelSelection ?: shellRow?.raw?.optJSONObject("modelSelection")?.toString(),
            connection = connection,
            draft = local.draft,
            sending = local.sending,
            interrupting = local.interrupting,
            unconfirmed = unconfirmed,
            notice = local.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState(ThreadView(threadId)))

    init {
        viewModelScope.launch {
            val found = connections.supervisor(environmentId) ?: return@launch
            found.start()
            supervisor.value = found
        }
        session.start()
        viewModelScope.launch {
            val saved = drafts.load(environmentId, threadId)
            savedDraft = saved
            // Text typed before the draft loaded wins.
            if (local.value.draft.isEmpty()) local.update { it.copy(draft = saved) }
            draftLoaded = true
        }
        viewModelScope.launch {
            local.map { it.draft }.distinctUntilChanged().drop(1).debounce(DRAFT_DEBOUNCE_MS).collect { saveDraft(it) }
        }
    }

    fun onDraftChange(text: String) {
        local.update { it.copy(draft = text) }
    }

    fun send() {
        val current = state.value
        val text = local.value.draft
        if (text.isBlank() || !current.connected || local.value.sending) return
        local.update { it.copy(sending = true, notice = null) }
        val modelSelection = current.modelSelection
        appScope.launch {
            val result = sender.send(transport(), environmentId, threadId, text, modelSelection)
            finish(result, sentText = text)
            local.update { it.copy(sending = false) }
        }
    }

    /** Resends an unconfirmed command, byte-identical. */
    fun retry(commandId: String) {
        val text = state.value.unconfirmed.firstOrNull { it.commandId == commandId }?.text
        local.update { it.copy(notice = null) }
        appScope.launch { finish(sender.retry(transport(), commandId), sentText = text) }
    }

    fun discard(commandId: String) {
        appScope.launch { sender.discard(commandId) }
    }

    fun interrupt() {
        val runId = state.value.view.activeRunId ?: return
        local.update { it.copy(interrupting = true, notice = null) }
        appScope.launch {
            val result = sender.interrupt(transport(), threadId, runId)
            local.update {
                it.copy(interrupting = false, notice = (result as? SendResult.Failed)?.let(::failed) ?: it.notice)
            }
        }
    }

    fun retryStream() {
        session.retry()
        supervisor.value?.retry()
    }

    /** The supervisor's live transport right now; null while it is not connected. */
    private fun transport() = supervisor.value?.connection?.value?.transport

    fun dismissNotice() {
        local.update { it.copy(notice = null) }
    }

    private suspend fun finish(result: SendResult, sentText: String?) {
        when (result) {
            is SendResult.Accepted -> {
                // Clear only what was sent; text typed meanwhile stays.
                if (sentText != null && local.value.draft == sentText) {
                    local.update { it.copy(draft = "") }
                    saveDraft("")
                }
            }
            is SendResult.OutcomeUnknown -> local.update { it.copy(notice = ThreadNotice.Unconfirmed(result.commandId)) }
            is SendResult.Failed -> local.update { it.copy(notice = failed(result)) }
        }
    }

    private fun failed(result: SendResult.Failed) = ThreadNotice.Failed(result.code, result.reason, result.requiredScope)

    private suspend fun saveDraft(text: String) {
        if (!draftLoaded || text == savedDraft) return
        drafts.save(environmentId, threadId, text)
        savedDraft = text
    }

    override fun onCleared() {
        // A change still inside the debounce window is written once more, off the screen's scope.
        val text = local.value.draft
        if (draftLoaded && text != savedDraft) appScope.launch { drafts.save(environmentId, threadId, text) }
        session.close()
    }

    private companion object {
        const val DRAFT_DEBOUNCE_MS = 400L
    }
}
