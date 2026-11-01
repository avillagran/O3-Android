package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.data.local.OutboxEntity
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** What the composer shows: the draft, whether it is being sent, and the commands left unconfirmed. */
data class ComposerState(
    val draft: String = "",
    val loaded: Boolean = false,
    /** Ephemeral UI echo identity; never persisted or included in a command. */
    val draftToken: String? = null,
    /** A send is in flight: the field is locked and holds the text being sent. */
    val sending: Boolean = false,
    /**
     * Commands whose outcome is unknown (`rpc_outcome_unknown`, or a send cut by a crash).
     * Each owns its text and offers Resend or Discard; nothing new is sent while one exists.
     */
    val unconfirmed: List<PendingCommand> = emptyList(),
) {
    /** True when the draft could be sent now, given a connection. */
    val canSend: Boolean get() = !sending && unconfirmed.isEmpty() && MessageSender.isSendable(draft)
}

/**
 * The composer of one thread: its draft (persisted through [drafts] [DRAFT_DEBOUNCE_MS]
 * after the last change) and sending it through [sender], so that one text is never sent
 * twice by accident:
 *
 * - while a send is in flight the draft is locked ([onDraftChange] is ignored);
 * - once the command is in the outbox the persisted draft is cleared, so after a crash the
 *   text lives only in the outbox row, which comes back as unconfirmed;
 * - accepted: the draft is cleared; outcome unknown: the text moves out of the draft into
 *   the outbox row, and sending stays disabled while such a row exists; any other failure:
 *   the draft keeps the text (and is persisted again);
 * - [retry] resends a row byte-identical; a definite rejection returns its text to the draft.
 *
 * Draft I/O runs in [scope] (the screen's); sends run in [appScope] through [sender], so
 * leaving the screen neither cuts a send nor loses its bookkeeping. [onResult] receives every
 * send, retry and its outcome, for the screen's notices.
 */
@OptIn(FlowPreview::class)
class ThreadComposer(
    private val environmentId: String,
    private val threadId: String,
    private val drafts: DraftRepository,
    outbox: OutboxRepository,
    private val sender: MessageSender,
    private val scope: CoroutineScope,
    private val appScope: CoroutineScope,
    private val transport: () -> T3Transport?,
    private val onResult: (SendResult) -> Unit = {},
) {
    private val draftSaveMutex = kotlinx.coroutines.sync.Mutex()
    private val _state = MutableStateFlow(ComposerState())
    val state: StateFlow<ComposerState> = _state.asStateFlow()

    @Volatile private var editedSinceInit = false
    private val saveRequests = MutableStateFlow(0L)
    @Volatile private var draftLoaded = false
    @Volatile private var savedDraft = ""

    init {
        scope.launch {
            val saved = drafts.load(environmentId, threadId)
            savedDraft = saved
            // Text typed before the draft loaded wins.
            _state.update { if (!editedSinceInit && it.draft.isEmpty() && !it.sending) it.copy(draft = saved, draftToken = null) else it }
            draftLoaded = true
            _state.update { it.copy(loaded = true) }
            if (editedSinceInit) saveRequests.update { it + 1 }
        }
        scope.launch {
            saveRequests.debounce(DRAFT_DEBOUNCE_MS).collect {
                val current = _state.value
                if (!current.sending) saveDraft(current.draft)
            }
        }
        scope.launch {
            outbox.observeThread(environmentId, threadId).collect { rows ->
                val unknown = rows.filter { it.state != OutboxEntity.STATE_SENDING }
                _state.update { it.copy(unconfirmed = unknown) }
            }
        }
    }

    fun onDraftChange(text: String, token: String? = null) {
        if (_state.value.sending) return
        editedSinceInit = true
        _state.update { if (it.sending) it else it.copy(draft = text, draftToken = token) }
        saveRequests.update { it + 1 }
    }

    /**
     * Sends the draft with [modelSelection]; false (nothing happens) when [ComposerState.canSend]
     * is false. The caller checks the connection first.
     */
    fun send(modelSelection: String?): Boolean {
        var text: String? = null
        _state.update { current ->
            if (!current.canSend) return@update current
            text = current.draft
            current.copy(sending = true)
        }
        val sent = text ?: return false
        appScope.launch {
            var result: SendResult = SendResult.Failed(MessageSender.NOT_CONNECTED)
            try {
                result = sender.send(transport(), environmentId, threadId, sent, modelSelection) {
                    // The outbox row owns the text from here on.
                    drafts.save(environmentId, threadId, "")
                    savedDraft = ""
                }
            } finally {
                val keep = result is SendResult.Failed
                _state.update { it.copy(draft = if (keep) sent else "", draftToken = null, sending = false) }
                if (keep) saveDraft(sent)
            }
            onResult(result)
        }
        return true
    }

    /** Resends an unconfirmed command, byte-identical. Only a refusal touches the draft. */
    fun retry(commandId: String) {
        appScope.launch {
            val text = _state.value.unconfirmed.firstOrNull { it.commandId == commandId }?.text
            val result = sender.retry(transport(), commandId)
            if (text != null && result is SendResult.Failed && result.rejected) restoreRejected(commandId, text)
            onResult(result)
        }
    }

    /** Forgets an unconfirmed command; it may still have reached the server. */
    fun discard(commandId: String) {
        appScope.launch { sender.discard(commandId) }
    }

    /** Save before retiring a receipt; a crash between the two steps cannot duplicate recovered text. */
    suspend fun editPending(commandId: String, restored: String): Boolean = draftSaveMutex.withLock {
        if (drafts.recoveryApplied(environmentId, threadId, "outbox-$commandId")) return@withLock true
        val before = _state.value
        if (!draftLoaded || before.sending || !_state.compareAndSet(before, before.copy(sending = true))) return@withLock false
        try {
            val merged = if (restored.isEmpty() || before.draft == restored) before.draft
                else listOf(before.draft, restored).filter { it.isNotEmpty() }.joinToString("\n\n")
            val saved = drafts.recoverOnce(environmentId, threadId, "outbox-$commandId", merged)
            savedDraft = saved
            editedSinceInit = true
            _state.update { it.copy(draft = saved, draftToken = null) }
            true
        } finally { _state.update { it.copy(sending = false) } }
    }

    /**
     * The refused row is already gone, so [text] has no other home. Waits out a concurrent
     * edit or the first draft load, which only [editPending] can observe.
     */
    suspend fun restoreRejected(commandId: String, text: String) {
        repeat(RESTORE_ATTEMPTS) {
            if (editPending(commandId, text)) return
            delay(RESTORE_RETRY_MS)
        }
    }

    /** Writes a change still inside the debounce window, off the screen's scope. */
    fun flush() {
        val current = _state.value
        if (draftLoaded && !current.sending && current.draft != savedDraft) {
            appScope.launch { drafts.save(environmentId, threadId, current.draft) }
        }
    }

    /** Text-only rewind recovery. Call while the shared journal excludes sends. */
    suspend fun recoverOnce(id: String, restored: String, append: suspend (String, String) -> String): Boolean = draftSaveMutex.withLock {
        if (!recoveryReady()) return@withLock false
        // An already-consumed id must not replace newer typing with an older Room row.
        if (drafts.recoveryApplied(environmentId, threadId, id)) return@withLock true
        val before = _state.value
        if (before.sending || before.unconfirmed.isNotEmpty()) return@withLock false
        if (!_state.compareAndSet(before, before.copy(sending = true))) return@withLock false
        try {
            val merged = append(before.draft, restored)
            val applied = drafts.recoverOnce(environmentId, threadId, id, merged)
            savedDraft = applied
            editedSinceInit = true
            _state.value = before.copy(draft = applied, draftToken = null, sending = false)
            true
        } finally { _state.update { it.copy(sending = false) } }
    }

    suspend fun recoveryApplied(id: String) = drafts.recoveryApplied(environmentId, threadId, id)

    fun recoveryReady() = draftLoaded && !_state.value.sending && _state.value.unconfirmed.isEmpty()

    private suspend fun saveDraft(text: String) = draftSaveMutex.withLock {
        if (!draftLoaded || text == savedDraft || text != _state.value.draft) return@withLock
        drafts.save(environmentId, threadId, text)
        savedDraft = text
    }

    companion object {
        const val DRAFT_DEBOUNCE_MS = 400L
        private const val RESTORE_ATTEMPTS = 100
        private const val RESTORE_RETRY_MS = 50L
    }
}
