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
    val queueConnection: Connection? = null,
    val composer: ComposerState = ComposerState(),
    val interrupting: Boolean = false,
    val notice: ThreadNotice? = null,
    val queueBusy: Boolean = false,
    val queueEdit: String? = null,
    val queueEditToken: String? = null,
    val modelCatalog: String? = null,
    val modelsLoading: Boolean = false,
    val respondingRequest: String? = null,
    val answeredRequests: Set<String> = emptySet(),
    val readingPosition: dev.avillagran.ot3android.core.data.prefs.ReadingPosition? = null,
    val readingPositionLoaded: Boolean = true,
) {
    val connected: Boolean get() = connection is ConnectionState.Connected

    val draftToken: String? get() = if (queueEdit != null) queueEditToken else composer.draftToken

    val draft: String get() = queueEdit?.let { org.json.JSONObject(it).optString("text") } ?: composer.draft

    val sending: Boolean get() = composer.sending || queueBusy || respondingRequest != null

    /** Commands whose outcome is unknown, each with Resend and Discard. */
    val unconfirmed: List<PendingCommand> get() = composer.unconfirmed

    val canSend: Boolean get() = connected && !view.stale && !sending && if (queueEdit != null) unconfirmed.isEmpty() else composer.canSend

    val canInterrupt: Boolean get() = view.working && view.activeRunId != null && connected && !view.stale && !interrupting
}

/** The screen's local state; the rest comes from the session, the supervisor and the composer. */
private data class Local(
    val interrupting: Boolean = false,
    val notice: ThreadNotice? = null,
    val queueBusy: Boolean = false,
    val queueEdit: String? = null,
    val queueEditToken: String? = null,
    val modelCatalog: String? = null,
    val modelsLoading: Boolean = false,
    val selectedModel: String? = null,
    val respondingRequest: String? = null,
    val answeredRequests: Set<String> = emptySet(),
    val readingPosition: dev.avillagran.ot3android.core.data.prefs.ReadingPosition? = null,
    val readingPositionLoaded: Boolean = true,
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
    private val preferences: dev.avillagran.ot3android.core.data.prefs.ComposerPreferences? = null,
    threadCache: dev.avillagran.ot3android.core.thread.ThreadCache? = null,
    cacheAuthority: suspend () -> String? = { environmentId },
    cacheAuthorityUpdates: Flow<String?>? = null,
) : ViewModel() {
    val editorIdentity: String get() = org.json.JSONArray().put(environmentId).put(threadId).toString()

    private val supervisor = MutableStateFlow<ConnectionSupervisor?>(null)
    private val local = MutableStateFlow(Local(readingPositionLoaded = preferences == null))
    private var modelSave: kotlinx.coroutines.Job? = null
    private val readingPositions = kotlinx.coroutines.channels.Channel<dev.avillagran.ot3android.core.data.prefs.ReadingPosition>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private val session = ThreadSession(
        threadId,
        supervisor.flatMapLatest { it?.connection ?: flowOf<Connection?>(null) },
        viewModelScope,
        cache = threadCache, cacheEnvironment = environmentId, cacheAuthority = cacheAuthority,
        cacheAuthorityUpdates = cacheAuthorityUpdates,
    )

    private val composer = ThreadComposer(
        environmentId, threadId, drafts, outbox, sender, viewModelScope, appScope,
        transport = ::transport,
        onResult = ::onResult,
    )

    private val connection: Flow<Pair<ConnectionState, Connection?>> = supervisor.flatMapLatest { current ->
        if (current == null) flowOf(ConnectionState.Disconnected to null)
        else combine(current.state, current.connection) { status, owner -> status to owner }
    }

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
            modelSelection = local.selectedModel ?: view.modelSelection ?: shellRow?.raw?.optJSONObject("modelSelection")?.toString(),
            connection = connection.first,
            queueConnection = connection.second,
            composer = composer,
            interrupting = local.interrupting,
            notice = local.notice,
            queueBusy = local.queueBusy,
            queueEdit = local.queueEdit,
            queueEditToken = local.queueEditToken,
            modelCatalog = local.modelCatalog,
            modelsLoading = local.modelsLoading,
            respondingRequest = local.respondingRequest,
            answeredRequests = local.answeredRequests,
            readingPosition = local.readingPosition, readingPositionLoaded = local.readingPositionLoaded,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState(ThreadView(threadId)))

    init {
        if (preferences != null) appScope.launch {
            for (position in readingPositions) {
                try { preferences.saveReadingPosition(environmentId, threadId, position) }
                catch (_: java.io.IOException) { /* Reading metadata is best effort. */ }
            }
        }
        viewModelScope.launch {
            val position = try { preferences?.readingPosition(environmentId, threadId) } catch (_: java.io.IOException) { null }
            local.update { it.copy(readingPosition = position, readingPositionLoaded = true) }
        }
        viewModelScope.launch {
            try {
                val saved = preferences?.model(environmentId, threadId)
                if (saved != null) local.update { if (it.selectedModel == null) it.copy(selectedModel = saved) else it }
            } catch (_: java.io.IOException) {
                local.update { it.copy(notice = ThreadNotice.Failed("model_draft_read_failed")) }
            }
        }
        viewModelScope.launch {
            val found = connections.supervisor(environmentId) ?: return@launch
            connections.startIfCurrent(found)
            // A managed route refresh replaces the supervisor without replacing this screen.
            connections.supervisors.map { it[environmentId] }.distinctUntilChanged().collect {
                connections.startIfCurrent(it)
                supervisor.value = it
            }
        }
        session.start()
    }

    fun saveReadingPosition(position: dev.avillagran.ot3android.core.data.prefs.ReadingPosition) {
        // Tiny local metadata survives leaving the reader; message-cache eviction never erases it.
        if (preferences != null) readingPositions.trySend(position)
    }

    fun onDraftChange(text: String) = onDraftChange(text, null)

    fun onDraftChange(text: String, token: String?) {
        if (local.value.queueEdit != null) {
            local.update { current ->
                if (current.queueBusy || current.queueEdit == null) current
                else current.copy(queueEdit = org.json.JSONObject(current.queueEdit).put("text", text).toString(), queueEditToken = token)
            }
            return
        }
        composer.onDraftChange(text, token)
    }

    /** Native catalog projection hides disabled/hidden models and preserves provider identities. */
    fun refreshModels() {
        if (local.value.modelsLoading) return
        val live = transport() ?: return
        local.update { it.copy(modelsLoading = true) }
        viewModelScope.launch {
            try {
                val config = live.call("server.getConfig", org.json.JSONObject()) as org.json.JSONObject
                val catalog = dev.avillagran.ot3android.core.transport.DomainEngine.shared.domain(
                    "models.catalog", org.json.JSONObject().put("config", config),
                ) as org.json.JSONObject
                if (transport() === live) local.update { it.copy(modelCatalog = catalog.toString()) }
            } catch (error: dev.avillagran.t3.T3Exception) {
                local.update { it.copy(notice = ThreadNotice.Failed(error.code, error.reason, error.requiredScope)) }
            } finally {
                local.update { it.copy(modelsLoading = false) }
            }
        }
    }

    fun selectModel(row: org.json.JSONObject) {
        if (state.value.sending || local.value.queueEdit != null || !row.optBoolean("available")) return
        // Values are the exact identities in models.catalog, not provider/display-name guesses.
        val selected = org.json.JSONObject().put("instanceId", row.getString("instanceId"))
            .put("model", row.getString("model")).put("options", org.json.JSONArray())
        saveModelSelection(selected)
    }

    fun favoriteModel(row: org.json.JSONObject, favorite: Boolean) {
        val catalog = local.value.modelCatalog?.let(::orgJson) ?: return
        if (!catalog.optBoolean("favoritesReady") || local.value.modelsLoading) return
        val live = transport() ?: return
        local.update { it.copy(modelsLoading = true) }
        viewModelScope.launch {
            try {
                val domain = dev.avillagran.ot3android.core.transport.DomainEngine.shared
                val payload = domain.domain("models.favoritePayload", org.json.JSONObject()
                    .put("provider", row.getString("instanceId")).put("model", row.getString("model"))
                    .put("favorite", favorite)) as org.json.JSONObject
                val settings = live.call("server.updateSettings", payload) as org.json.JSONObject
                val preferences = domain.domain("models.preferences", org.json.JSONObject().put("settings", settings)) as org.json.JSONObject
                if (transport() === live) local.update { current ->
                    val updated = current.modelCatalog?.let(::orgJson) ?: catalog
                    current.copy(modelCatalog = updated.put("favorites", preferences.getJSONArray("favorites"))
                        .put("favoritesReady", preferences.getBoolean("favoritesReady")).toString())
                }
            } catch (error: dev.avillagran.t3.T3Exception) {
                local.update { it.copy(notice = ThreadNotice.Failed(error.code, error.reason, error.requiredScope)) }
            } finally {
                local.update { it.copy(modelsLoading = false) }
            }
        }
    }

    fun selectModelOption(id: String, value: Any) {
        if (state.value.sending || local.value.queueEdit != null) return
        val selection = (local.value.selectedModel ?: state.value.modelSelection)?.let(::orgJson) ?: return
        val previous = selection.optJSONArray("options") ?: org.json.JSONArray()
        val options = org.json.JSONArray()
        for (i in 0 until previous.length()) {
            val option = previous.getJSONObject(i)
            if (option.optString("id") != id) options.put(option)
        }
        options.put(org.json.JSONObject().put("id", id).put("value", value))
        saveModelSelection(selection.put("options", options))
    }

    private fun saveModelSelection(selection: org.json.JSONObject) {
        val bytes = selection.toString()
        local.update { it.copy(selectedModel = bytes) }
        modelSave?.cancel()
        modelSave = appScope.launch {
            try { preferences?.saveModel(environmentId, threadId, bytes) }
            catch (_: java.io.IOException) {
                local.update { it.copy(notice = ThreadNotice.Failed("model_draft_save_failed")) }
            }
        }
    }

    private fun orgJson(value: String) = org.json.JSONObject(value)

    /** Editing never replaces the unsent Room draft. The server stream owns the queue row. */
    fun editQueued(row: org.json.JSONObject) {
        if (!local.value.queueBusy) local.update { it.copy(queueEdit = row.toString(), queueEditToken = null) }
    }

    fun cancelQueueEdit() {
        if (!local.value.queueBusy) local.update { it.copy(queueEdit = null, queueEditToken = null) }
    }

    fun selectRuntimeMode(mode: String) {
        val current = state.value
        if (!current.connected || current.view.stale || current.sending || local.value.queueBusy ||
            local.value.respondingRequest != null || current.queueEdit != null || current.unconfirmed.isNotEmpty()) return
        local.update { it.copy(queueBusy = true, notice = null) }
        appScope.launch {
            try { onResult(sender.runtimeMode(transport(), environmentId, threadId, mode)) }
            finally { local.update { it.copy(queueBusy = false) } }
        }
    }

    fun respondApproval(requestId: String, decision: String) {
        val current = state.value
        if (!current.connected || current.view.stale || current.sending || current.unconfirmed.isNotEmpty() ||
            local.value.respondingRequest != null || requestId in local.value.answeredRequests) return
        local.update { it.copy(respondingRequest = requestId, notice = null) }
        appScope.launch {
            try {
                val projection = session.projectionSnapshot() ?: return@launch
                val result = sender.approve(transport(), environmentId, threadId, requestId, decision, projection)
                if (result is SendResult.Accepted) local.update {
                    it.copy(answeredRequests = it.answeredRequests + requestId)
                }
                onResult(result)
            } finally { local.update { it.copy(respondingRequest = null) } }
        }
    }

    fun respondQuestion(requestId: String, action: String, answers: org.json.JSONObject) {
        val current = state.value
        if (!current.connected || current.view.stale || current.sending || current.unconfirmed.isNotEmpty() ||
            local.value.respondingRequest != null || requestId in local.value.answeredRequests) return
        local.update { it.copy(respondingRequest = requestId, notice = null) }
        appScope.launch {
            try {
                val result = sender.answer(transport(), environmentId, threadId, requestId, action, answers)
                if (result is SendResult.Accepted) local.update { it.copy(answeredRequests = it.answeredRequests + requestId) }
                onResult(result)
            } finally { local.update { it.copy(respondingRequest = null) } }
        }
    }

    /** Queue capability and row data are already projected by Rust's timeline.view. */
    fun queueCommand(arguments: org.json.JSONObject) {
        if (!state.value.connected || state.value.view.stale || state.value.sending || local.value.queueBusy || local.value.respondingRequest != null || state.value.unconfirmed.isNotEmpty()) return
        local.update { it.copy(queueBusy = true, notice = null) }
        appScope.launch {
            try {
                val result = sender.queue(transport(), environmentId, threadId, arguments)
                onResult(result)
                if (arguments.optString("action") == "edit" && result !is SendResult.Failed) {
                    local.update { it.copy(queueEdit = null, queueEditToken = null) }
                }
            } finally {
                local.update { it.copy(queueBusy = false) }
            }
        }
    }

    fun send() {
        val current = state.value
        if (!current.connected || current.view.stale || local.value.queueBusy || local.value.respondingRequest != null) return
        val queuedEdit = local.value.queueEdit
        if (queuedEdit != null) {
            queueCommand(queueEditArguments(org.json.JSONObject(queuedEdit)))
            return
        }
        if (composer.send(local.value.selectedModel ?: current.modelSelection)) local.update { it.copy(notice = null) }
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
        if (!state.value.canInterrupt || local.value.interrupting) return
        val runId = state.value.view.activeRunId ?: return
        local.update { it.copy(interrupting = true, notice = null) }
        appScope.launch {
            val result = sender.interrupt(transport(), threadId, runId)
            local.update {
                it.copy(interrupting = false, notice = (result as? SendResult.Failed)?.let(::failed) ?: it.notice)
            }
        }
    }

    /** On-demand read; scope and response validation belong to Rust. Never dispatches. */
    suspend fun inspect(reference: org.json.JSONObject): org.json.JSONObject? {
        val transport = transport() ?: return null
        val args = org.json.JSONObject().put("reference", reference).put("environment", environmentId).put("threadId", threadId)
        val domain = dev.avillagran.ot3android.core.transport.DomainEngine.shared
        val request = domain.domain("toolInspector.request", args) as org.json.JSONObject
        val response = transport.call(request.getString("method"), request.getJSONObject("payload")) as org.json.JSONObject
        return domain.domain("toolInspector.parse", org.json.JSONObject(args.toString()).put("response", response)) as org.json.JSONObject
    }

    // One ephemeral UI owner only; never serialized in a server request or draft.
    private var imageOwnerConnection: Connection? = null
    private var imageOwnerToken = java.util.UUID.randomUUID().toString()
    fun imageOwner(connection: Connection?): String {
        if (connection !== imageOwnerConnection) {
            imageOwnerConnection = connection
            imageOwnerToken = java.util.UUID.randomUUID().toString()
        }
        return org.json.JSONArray().put(environmentId).put(threadId).put(imageOwnerToken).toString()
    }
    val imageConnection = supervisor.flatMapLatest { it?.connection ?: flowOf<Connection?>(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun isImageConnectionCurrent(expected: Connection?): Boolean =
        expected != null && supervisor.value?.connection?.value === expected

    suspend fun readImage(attachment: org.json.JSONObject): ByteArray? {
        val owner = supervisor.value ?: return null
        val current = owner.connection.value ?: return null
        val source = attachment.optString("source")
        val playable = source.isEmpty() && dev.avillagran.ot3android.core.thread.MessageMedia.mime(attachment.optString("name"), attachment.optString("mimeType")) != null
        val bytes = if (playable) {
            current.transport.mediaAttachment(attachment.getString("id"), attachment.optString("name"), attachment.optString("mimeType"))
        } else if (source.isEmpty()) {
            current.transport.image(attachment.getString("id"), attachment.optString("name"), attachment.getString("mimeType"))
        } else if (dev.avillagran.ot3android.core.thread.PublicMarkdownImage.isPublic(source)) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.avillagran.ot3android.core.thread.PublicMarkdownImage.read(source)
            }
        } else {
            current.transport.mediaImage(threadId, dev.avillagran.ot3android.core.thread.MarkdownImageSource.hostPath(source))
        }
        if (!playable) dev.avillagran.ot3android.core.thread.PublicMarkdownImage.mime(bytes)
        return bytes.takeIf { supervisor.value === owner && owner.connection.value === current }
    }

    suspend fun readDiff(summary: org.json.JSONObject, ignoreWhitespace: Boolean): org.json.JSONObject? {
        val owner = supervisor.value ?: return null
        val current = owner.connection.value ?: return null
        val result = dev.avillagran.ot3android.core.thread.ThreadDiff.read(current.transport, environmentId, threadId, summary, ignoreWhitespace)
        return result.takeIf { supervisor.value === owner && owner.connection.value === current }
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
        readingPositions.close()
        composer.flush()
        session.close()
    }
}

/** UI row metadata is not an argument of the strict native queue.command contract. */
internal fun queueEditArguments(row: org.json.JSONObject): org.json.JSONObject = org.json.JSONObject()
    .put("action", "edit").put("runId", row.getString("runId")).put("text", row.optString("text"))
    .put("attachments", row.optJSONArray("attachments") ?: org.json.JSONArray())
    .apply { row.optJSONObject("context")?.let { put("context", it) } }
