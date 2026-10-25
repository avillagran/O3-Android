package dev.avillagran.ot3android.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import dev.avillagran.ot3android.core.shell.ShellThread
import dev.avillagran.ot3android.core.thread.ComposerState
import dev.avillagran.ot3android.core.thread.DraftRepository
import dev.avillagran.ot3android.core.thread.MessageSender
import dev.avillagran.ot3android.core.thread.OutboxRepository
import dev.avillagran.ot3android.core.thread.PendingCommand
import dev.avillagran.ot3android.core.thread.SendResult
import dev.avillagran.ot3android.core.thread.ThreadComposer
import dev.avillagran.ot3android.core.thread.ThreadSession
import dev.avillagran.ot3android.core.thread.ThreadView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val composer: ComposerState = ComposerState(),
    val interrupting: Boolean = false,
    val notice: ThreadNotice? = null,
) {
    val connected: Boolean get() = connection is ConnectionState.Connected

    val draft: String get() = composer.draft

    val sending: Boolean get() = composer.sending

    /** Commands whose outcome is unknown, each with Resend and Discard. */
    val unconfirmed: List<PendingCommand> get() = composer.unconfirmed

    val canSend: Boolean get() = composer.canSend && connected

    val canInterrupt: Boolean get() = view.working && view.activeRunId != null && connected && !interrupting
}

/** The screen's local state; the rest comes from the session, the supervisor and the composer. */
private data class Local(
    val interrupting: Boolean = false,
    val notice: ThreadNotice? = null,
)

/**
 * One open thread: a [ThreadSession] on the environment's supervisor, the [ThreadComposer]
 * with its persisted draft, and interrupts through [MessageSender]. Sends and their
 * bookkeeping run in [appScope], so leaving the screen does not cut them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModel(
    private val environmentId: String,
    private val threadId: String,
    connections: ConnectionManager,
    drafts: DraftRepository,
    outbox: OutboxRepository,
    private val sender: MessageSender,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val supervisor = MutableStateFlow<ConnectionSupervisor?>(null)
    private val local = MutableStateFlow(Local())

    private val session = ThreadSession(
        threadId,
        supervisor.flatMapLatest { it?.connection ?: flowOf<Connection?>(null) },
        viewModelScope,
    )

    private val composer = ThreadComposer(
        environmentId, threadId, drafts, outbox, sender, viewModelScope, appScope,
        transport = ::transport,
        onResult = ::onResult,
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
        composer.state,
    ) { view, connection, shellRow, local, composer ->
        ThreadUiState(
            view = view,
            title = view.title ?: shellRow?.title,
            modelSelection = view.modelSelection ?: shellRow?.raw?.optJSONObject("modelSelection")?.toString(),
            connection = connection,
            composer = composer,
            interrupting = local.interrupting,
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
    }

    fun onDraftChange(text: String) {
        composer.onDraftChange(text)
    }

    fun send() {
        val current = state.value
        if (!current.connected) return
        if (composer.send(current.modelSelection)) local.update { it.copy(notice = null) }
    }

    /** Resends an unconfirmed command, byte-identical; the draft is not touched. */
    fun retry(commandId: String) {
        local.update { it.copy(notice = null) }
        composer.retry(commandId)
    }

    fun discard(commandId: String) {
        composer.discard(commandId)
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

    private fun onResult(result: SendResult) {
        when (result) {
            is SendResult.Accepted -> Unit
            is SendResult.OutcomeUnknown -> local.update { it.copy(notice = ThreadNotice.Unconfirmed(result.commandId)) }
            is SendResult.Failed -> local.update { it.copy(notice = failed(result)) }
        }
    }

    private fun failed(result: SendResult.Failed) = ThreadNotice.Failed(result.code, result.reason, result.requiredScope)

    override fun onCleared() {
        composer.flush()
        session.close()
    }
}
