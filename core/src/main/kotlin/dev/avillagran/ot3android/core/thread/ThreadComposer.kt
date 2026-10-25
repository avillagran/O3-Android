package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.data.local.OutboxEntity
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the composer shows: the draft, whether it is being sent, and the commands left unconfirmed. */
data class ComposerState(
    val draft: String = "",
    /** A send is in flight: the field is locked and holds the text being sent. */
    val sending: Boolean = false,
    /**
     * Commands whose outcome is unknown (`rpc_outcome_unknown`, or a send cut by a crash).
     * Each owns its text and offers Resend or Discard; nothing new is sent while one exists.
     */
    val unconfirmed: List<PendingCommand> = emptyList(),
) {
    /** True when the draft could be sent now, given a connection. */
    val canSend: Boolean get() = draft.isNotBlank() && !sending && unconfirmed.isEmpty()
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
 * - [retry] resends a row byte-identical and never touches the draft.
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
    private val _state = MutableStateFlow(ComposerState())
    val state: StateFlow<ComposerState> = _state.asStateFlow()

    @Volatile private var draftLoaded = false
    @Volatile private var savedDraft = ""

    init {
        scope.launch {
            val saved = drafts.load(environmentId, threadId)
            savedDraft = saved
            // Text typed before the draft loaded wins.
            _state.update { if (it.draft.isEmpty() && !it.sending) it.copy(draft = saved) else it }
            draftLoaded = true
        }
        scope.launch {
            _state.map { it.draft to it.sending }.distinctUntilChanged().drop(1).debounce(DRAFT_DEBOUNCE_MS)
                .collect { (draft, sending) -> if (!sending) saveDraft(draft) }
        }
        scope.launch {
            outbox.observeThread(environmentId, threadId).collect { rows ->
                val unknown = rows.filter { it.state == OutboxEntity.STATE_UNKNOWN }
                _state.update { it.copy(unconfirmed = unknown) }
            }
        }
    }

    fun onDraftChange(text: String) {
        _state.update { if (it.sending) it else it.copy(draft = text) }
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
                _state.update { it.copy(draft = if (keep) sent else "", sending = false) }
                if (keep) saveDraft(sent)
            }
            onResult(result)
        }
        return true
    }

    /** Resends an unconfirmed command, byte-identical; the draft is not touched. */
    fun retry(commandId: String) {
        appScope.launch { onResult(sender.retry(transport(), commandId)) }
    }

    /** Forgets an unconfirmed command; it may still have reached the server. */
    fun discard(commandId: String) {
        appScope.launch { sender.discard(commandId) }
    }

    /** Writes a change still inside the debounce window, off the screen's scope. */
    fun flush() {
        val current = _state.value
        if (draftLoaded && !current.sending && current.draft != savedDraft) {
            appScope.launch { drafts.save(environmentId, threadId, current.draft) }
        }
    }

    private suspend fun saveDraft(text: String) {
        if (!draftLoaded || text == savedDraft) return
        drafts.save(environmentId, threadId, text)
        savedDraft = text
    }

    companion object {
        const val DRAFT_DEBOUNCE_MS = 400L
    }
}
