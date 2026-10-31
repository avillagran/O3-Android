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
import kotlinx.coroutines.flow.first
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
import kotlinx.coroutines.sync.withLock

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
    val composerScope: dev.avillagran.ot3android.core.composer.ComposerScope? = null,
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
    private val outbox: OutboxRepository,
    private val sender: MessageSender,
    private val appScope: CoroutineScope,
    private val preferences: dev.avillagran.ot3android.core.data.prefs.ComposerPreferences? = null,
    threadCache: dev.avillagran.ot3android.core.thread.ThreadCache? = null,
    private val cacheAuthority: suspend () -> String? = { environmentId },
    cacheAuthorityUpdates: Flow<String?>? = null,
    attachmentDirectory: java.io.File? = null,
    private val gitJournal: dev.avillagran.ot3android.core.git.GitJournal? = null,
    private val checkoutAdmission: dev.avillagran.ot3android.core.git.CheckoutAdmission? = null,
    private val clientPreferences: dev.avillagran.ot3android.core.settings.GeneralClientPreferences? = null,
) : ViewModel() {
    val durableComposerAvailable = attachmentDirectory != null
    private val attachmentFile = attachmentDirectory?.let { directory ->
        directory.mkdirs()
        val key = java.security.MessageDigest.getInstance("SHA-256").digest((environmentId + "/" + threadId).toByteArray()).joinToString("") { "%02x".format(it) }
        android.util.AtomicFile(java.io.File(directory, key + ".json"))
    }
    val attachmentDraft = MutableStateFlow<List<dev.avillagran.ot3android.core.composer.ComposerAttachment>>(emptyList())
    val attachmentStorageError = MutableStateFlow(false)
    val attachmentPending = MutableStateFlow<String?>(null)
    private var attachmentCommand: String? = null
    private var attachmentAuthority: String? = null
    init {
        attachmentFile?.let { file ->
            try { if (file.baseFile.exists()) {
                val saved = org.json.JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                attachmentAuthority = saved.optString("authority").takeIf { it.isNotEmpty() }
                attachmentCommand = saved.optString("command").takeIf { it.isNotEmpty() }
                attachmentPending.value = attachmentCommand?.let { org.json.JSONObject(it).getString("commandId") }
                val files = saved.optJSONArray("attachments") ?: org.json.JSONArray()
                attachmentDraft.value = (0 until files.length()).map { i ->
                    val item = files.getJSONObject(i)
                    dev.avillagran.ot3android.core.composer.ComposerAttachment(item.getString("id"), item.getString("name"),
                        if (item.has("descriptor")) dev.avillagran.ot3android.core.composer.UploadStatus.Ready else dev.avillagran.ot3android.core.composer.UploadStatus.Failed,
                        item.optString("descriptor").takeIf { it.isNotEmpty() })
                }
            } } catch (_: Exception) { attachmentStorageError.value = true }
        }
    }
    private fun persistAttachments(): Boolean = try {
        val saved = org.json.JSONObject().put("authority", attachmentAuthority ?: "").put("command", attachmentCommand ?: "").put("attachments", org.json.JSONArray(attachmentDraft.value.map {
            org.json.JSONObject().put("id", it.id).put("name", it.name).apply { it.descriptor?.let { value -> put("descriptor", value) } }
        }))
        attachmentFile?.let { file ->
            val stream = file.startWrite()
            try { stream.write(saved.toString().toByteArray()); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        }
        true
    } catch (_: Exception) { attachmentStorageError.value = true; false }
    private suspend fun verifyAttachmentAuthority() {
        val authority = cacheAuthority() ?: error("attachment_authority_unavailable")
        val previous = attachmentAuthority
        check(previous == authority || (attachmentCommand == null && attachmentDraft.value.none { it.descriptor != null })) { "attachment_authority_changed" }
        attachmentAuthority = authority
    }
    fun removeAttachment(id: String) {
        if (local.value.queueBusy || attachmentPending.value != null || state.value.sending) return
        attachmentDraft.value = attachmentDraft.value.filterNot { it.id == id }
        persistAttachments()
    }
    fun importAttachment(context: android.content.Context, uri: String) {
        if (attachmentStorageError.value || local.value.queueBusy || !state.value.connected || state.value.sending || attachmentPending.value != null || attachmentDraft.value.size >= 100) return
        if (checkpoint.value.busy || checkpoint.value.record?.let { org.json.JSONObject(it).optString("state") !in setOf("complete", "failed", "reviewed") } == true) return
        val id = java.util.UUID.randomUUID().toString()
        attachmentDraft.value += dev.avillagran.ot3android.core.composer.ComposerAttachment(id, "attachment", dev.avillagran.ot3android.core.composer.UploadStatus.Uploading)
        persistAttachments()
        viewModelScope.launch {
            try {
                val descriptor = attachmentImportLock.withLock { verifyAttachmentAuthority(); uploadAttachment(context, uri) }
                if (attachmentDraft.value.any { it.id == id }) {
                    attachmentDraft.value = attachmentDraft.value.map { if (it.id == id) it.copy(name = descriptor.optString("name", it.name),
                        status = dev.avillagran.ot3android.core.composer.UploadStatus.Ready, descriptor = descriptor.toString()) else it }
                }
            } catch (error: Exception) {
                attachmentDraft.value = attachmentDraft.value.map { if (it.id == id) it.copy(status = dev.avillagran.ot3android.core.composer.UploadStatus.Failed) else it }
                if (error is kotlinx.coroutines.CancellationException) throw error
            } finally { persistAttachments() }
        }
    }
    fun dispatchAttachments(oppositeOnce: Boolean = false) {
        if (attachmentStorageError.value || local.value.queueBusy || state.value.composer.sending || local.value.respondingRequest != null || !state.value.connected || state.value.view.stale || state.value.queueEdit != null) return
        val pending = attachmentCommand
        if (pending == null && (!state.value.unconfirmed.isEmpty() || (attachmentDraft.value.isEmpty() && state.value.draft.isBlank()) || attachmentDraft.value.any { it.descriptor == null })) return
        local.update { it.copy(queueBusy = true) }
        appScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            try {
                verifyAttachmentAuthority()
                val args = pending?.let { org.json.JSONObject(it) } ?: MessageSender.messageCommand("ot3a-" + java.util.UUID.randomUUID(),
                    "ot3a-" + java.util.UUID.randomUUID(), threadId, state.value.draft, state.value.modelSelection)
                    .put("attachments", org.json.JSONArray(attachmentDraft.value.map { org.json.JSONObject(it.descriptor!!) }))
                if (pending == null) {
                    val preferences = clientPreferences?.settings?.first()
                    var delivery = if (state.value.view.activeRunId == null) "auto" else if ((state.value.view.timeline?.optJSONArray("approvals")?.length() ?: 0) > 0) "steer" else preferences?.optString("followUpBehavior", "queue") ?: "queue"
                    if (oppositeOnce && delivery != "auto") delivery = if (delivery == "queue") "steer" else "queue"
                    args.put("deliveryIntent", delivery)
                }
                val id = args.getString("commandId")
                attachmentCommand = args.toString()
                attachmentPending.value = id
                check(persistAttachments()) { "attachment_storage_unavailable" }
                if (outbox.find(id) == null) outbox.add(environmentId, threadId, id, args.toString())
                val result = sender.retry(transport(), id)
                if (result is SendResult.Accepted) {
                    attachmentDraft.value = emptyList(); attachmentCommand = null; attachmentPending.value = null
                    persistAttachments()
                    if (state.value.draft == args.optString("text")) composer.onDraftChange("")
                }
                onResult(result)
            } catch (error: Exception) {
                local.update { it.copy(notice = ThreadNotice.Failed("attachment_send_unconfirmed")) }
                if (error is kotlinx.coroutines.CancellationException) throw error
            } finally { local.update { it.copy(queueBusy = false) } }
        }
    }

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

    // Retain only this thread's last authenticated composer identity while offline.
    // Delivery remains gated by connection/staleness and attachment authority.
    private var lastComposerScope: dev.avillagran.ot3android.core.composer.ComposerScope? = null
    val state: StateFlow<ThreadUiState> = combine(
        session.view,
        connection,
        shellRow,
        local,
        composer.state,
    ) { view, connection, shellRow, local, composer ->
        val liveScope = (connection.first as? ConnectionState.Connected)?.descriptor?.environmentId?.let { server ->
            shellRow?.projectId?.let { project -> dev.avillagran.ot3android.core.composer.ComposerScope(server, project, threadId) }
        }
        if (liveScope != null) lastComposerScope = liveScope
        ThreadUiState(
            view = view,
            title = view.title ?: shellRow?.title,
            composerScope = liveScope ?: lastComposerScope,
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

    suspend fun uploadAttachment(context: android.content.Context, uri: String): org.json.JSONObject {
        val captured = supervisor.value?.connection?.value ?: error("not_connected")
        val upload = captured.transport as? dev.avillagran.ot3android.ui.thread.composer.ComposerBinaryUpload ?: error("upload_unavailable")
        val result = dev.avillagran.ot3android.ui.thread.composer.ComposerAttachmentImporter(context, upload).upload(uri)
        check(supervisor.value?.connection?.value == captured) { "connection_changed" }
        return result
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
        if (commandId == attachmentPending.value) { dispatchAttachments(); return }
        local.update { it.copy(notice = null) }
        composer.retry(commandId)
    }

    fun discard(commandId: String) {
        if (local.value.queueBusy || state.value.composer.sending) return
        if (commandId == attachmentPending.value) {
            attachmentCommand = null; attachmentPending.value = null; attachmentDraft.value = emptyList(); persistAttachments()
        }
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

    val checkpointMessages = MutableStateFlow<Set<String>>(emptySet())
    val checkpoint = MutableStateFlow(CheckpointUi())
    private val rollback by lazy { gitJournal?.let { dev.avillagran.ot3android.core.git.CheckpointRollback(it) } }
    private val checkpointTarget get() = org.json.JSONObject().put("environment", environmentId).put("thread", threadId)
    init {
        if (gitJournal != null) viewModelScope.launch {
            session.view.collect {
                val projection = session.projectionSnapshot()
                val ids = mutableSetOf<String>()
                if (projection != null) {
                    val messages = projection.optJSONArray("messages") ?: org.json.JSONArray()
                    for (i in 0 until messages.length()) {
                        val message = messages.getJSONObject(i)
                        // Generic attachment recovery is unavailable; never rewind away the only bytes.
                        if (!recoverableCheckpointText(message)) continue
                        try {
                            if (dev.avillagran.ot3android.core.transport.DomainEngine.shared.domain("checkpointRevert.canPreview",
                                org.json.JSONObject().put("projection", projection).put("message", message)) == true) ids.add(message.getString("id"))
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { }
                    }
                }
                checkpointMessages.value = ids
                val pending = gitJournal.records().lastOrNull { record -> record.optString("kind") == "rollback" &&
                    record.optJSONObject("input")?.optString("environment") == environmentId &&
                    record.optJSONObject("input")?.optString("thread") == threadId && record.optString("state") !in setOf("complete", "failed", "reviewed") }
                if (pending != null && projection != null) {
                    try {
                        var observed = rollback!!.observe(pending.getString("id"), environmentId, projection)
                        if (observed.optString("state") == "recovering" && composer.recoveryApplied(observed.getString("id"))) {
                            gitJournal.transition(observed.getString("id"), "complete")
                            observed = gitJournal.find(observed.getString("id"))!!
                        }
                        val before = checkpoint.value
                        val firstDiscovery = before.record?.let { org.json.JSONObject(it).optString("id") } != observed.getString("id")
                        val unresolved = observed.optString("state") !in setOf("complete", "failed", "reviewed")
                        checkpoint.value = before.copy(record = observed.toString(), open = before.open || (firstDiscovery && unresolved))
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) {
                        val before = checkpoint.value
                        val firstDiscovery = before.record?.let { org.json.JSONObject(it).optString("id") } != pending.getString("id")
                        checkpoint.value = before.copy(record = pending.toString(), open = before.open || firstDiscovery, failed = true)
                    }
                }
            }
        }
    }
    private fun recoverableCheckpointText(message: org.json.JSONObject): Boolean {
        if (message.optJSONArray("attachments")?.length() != 0) return false
        return when (val context = message.opt("context")) {
            null, org.json.JSONObject.NULL -> true
            is org.json.JSONObject -> context.length() == 0
            is org.json.JSONArray -> context.length() == 0
            else -> false
        }
    }
    private suspend fun checkpointSafety(): org.json.JSONObject {
        val live = transport() ?: error("checkpoint_offline")
        val auth = live.authSession()
        val permissions = auth.optJSONArray("permissions") ?: auth.optJSONArray("scopes")
        val config = live.call("server.getConfig") as org.json.JSONObject
        val projection = session.projectionSnapshot() ?: error("checkpoint_projection_unavailable")
        val thread = projection.getJSONObject("thread")
        val instance = thread.optString("providerInstanceId").takeIf { it.isNotBlank() }
            ?: thread.optJSONObject("modelSelection")?.optString("instanceId")
        val providers = config.optJSONArray("providers") ?: org.json.JSONArray()
        val provider = (0 until providers.length()).map { providers.getJSONObject(it) }.find { it.optString("instanceId") == instance && it.optBoolean("enabled") }
        check(transport() === live)
        return org.json.JSONObject().put("canOperate", auth.optBoolean("authenticated") && permissions != null &&
            (0 until permissions.length()).any { permissions.optString(it) == "orchestration:operate" })
            .put("connected", state.value.connected && !state.value.view.stale)
            .put("supportsConversationRollback", provider?.opt("supportsConversationRollback") == true)
            .put("working", state.value.view.working).put("sendBusy", state.value.sending || local.value.queueBusy || local.value.respondingRequest != null || state.value.unconfirmed.isNotEmpty())
            .put("pendingAttachments", attachmentDraft.value.isNotEmpty() || attachmentCommand != null || attachmentStorageError.value)
            .put("connecting", !state.value.connected).put("rewinding", false)
    }
    fun previewCheckpoint(messageId: String) {
        if (messageId !in checkpointMessages.value || rollback == null || checkpoint.value.busy) return
        val existing = gitJournal?.records()?.lastOrNull { it.optString("kind") == "rollback" &&
            it.optJSONObject("input")?.optString("environment") == environmentId && it.optJSONObject("input")?.optString("thread") == threadId &&
            it.optString("state") !in setOf("complete", "failed", "reviewed") }
        if (existing != null) { checkpoint.value = CheckpointUi(open = true, record = existing.toString()); return }
        checkpoint.value = CheckpointUi(open = true, busy = true)
        viewModelScope.launch {
            try {
                val projection = session.projectionSnapshot() ?: error("checkpoint_projection_unavailable")
                val preview = rollback!!.preview(projection, messageId, checkpointSafety())
                check(recoverableCheckpointText(preview.getJSONObject("message")))
                checkpoint.value = CheckpointUi(open = true, preview = preview.toString(), canRestoreFiles = shellRow.first()?.raw?.optString("worktreePath")?.let { it.isNotBlank() && it != "null" } == true)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { checkpoint.value = CheckpointUi(open = true, failed = true) }
        }
    }
    fun closeCheckpoint() { checkpoint.value = checkpoint.value.copy(open = false) }
    fun confirmCheckpoint(restoreFiles: Boolean, acknowledged: Boolean) {
        val preview = checkpoint.value.preview ?: return
        if (checkpoint.value.busy || checkpoint.value.record != null) return
        checkpoint.value = checkpoint.value.copy(busy = true)
        // Keep dispatch alive beyond navigation, but never replay it on re-entry.
        appScope.launch {
            try {
                val current = transport() ?: error("checkpoint_offline")
                checkoutAdmission!!.git(environmentId, { outbox.observeEnvironment(environmentId).first().isEmpty() }) {
                    val safety = checkpointSafety()
                    check(transport() === current)
                    val fresh = current.boundedThread(threadId).getJSONObject("projection")
                    check(fresh.getJSONObject("thread").getString("id") == threadId && transport() === current)
                    val captured = org.json.JSONObject(preview)
                    val message = dev.avillagran.ot3android.core.transport.DomainEngine.shared.domain("checkpointRevert.recoveryMessage",
                        org.json.JSONObject().put("projection", fresh).put("reference", captured.getJSONObject("reference"))) as org.json.JSONObject
                    check(recoverableCheckpointText(message) && message.getString("text") == captured.getJSONObject("message").getString("text"))
                    check(attachmentDraft.value.isEmpty() && attachmentCommand == null && durableComposerAvailable && composer.recoveryReady())
                    val record = rollback!!.confirm(current, checkpointTarget, org.json.JSONObject(preview), fresh, safety, restoreFiles, acknowledged, true)
                    checkpoint.value = checkpoint.value.copy(record = record.toString(), busy = false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                val saved = gitJournal?.find(org.json.JSONObject(preview).getString("requestId"))
                checkpoint.value = checkpoint.value.copy(record = saved?.toString(), busy = false, failed = true)
            }
        }
    }
    fun refreshCheckpoint() {
        val current = checkpoint.value
        val record = current.record
        if (record == null) {
            current.preview?.let { previewCheckpoint(org.json.JSONObject(it).getJSONObject("reference").getString("messageId")) }
            return
        }
        viewModelScope.launch {
            try {
                val live = transport() ?: error("checkpoint_offline")
                val projection = live.boundedThread(threadId).getJSONObject("projection")
                check(transport() === live && projection.getJSONObject("thread").getString("id") == threadId)
                val observed = rollback!!.observe(org.json.JSONObject(record).getString("id"), environmentId, projection)
                checkpoint.value = current.copy(record = observed.toString(), failed = false)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { checkpoint.value = current.copy(failed = true) }
        }
    }
    fun recoverCheckpoint() {
        val record = checkpoint.value.record ?: return
        if (checkpoint.value.busy) return
        checkpoint.value = checkpoint.value.copy(busy = true)
        appScope.launch {
            try {
                val recovered = rollback!!.recover(org.json.JSONObject(record).getString("id")) { id, message ->
                    check(message.getJSONArray("attachments").length() == 0 && attachmentDraft.value.isEmpty() && attachmentCommand == null)
                    composer.recoverOnce(id, message.getString("text"), rollback!!::appendPrompt)
                }
                checkpoint.value = checkpoint.value.copy(record = recovered.toString(), busy = false)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { checkpoint.value = checkpoint.value.copy(busy = false, failed = true) }
        }
    }

    /** On-demand read; scope and response validation belong to Rust. Never dispatches. */
    suspend fun inspect(reference: org.json.JSONObject): org.json.JSONObject? {
        val owner = supervisor.value ?: return null
        val current = owner.connection.value ?: return null
        val transport = current.transport
        val args = org.json.JSONObject().put("reference", reference).put("environment", environmentId).put("threadId", threadId)
        val domain = dev.avillagran.ot3android.core.transport.DomainEngine.shared
        val request = domain.domain("toolInspector.request", args) as org.json.JSONObject
        val response = transport.call(request.getString("method"), request.getJSONObject("payload")) as org.json.JSONObject
        if (supervisor.value !== owner || owner.connection.value !== current) return null
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
        val cache = dev.avillagran.ot3android.core.thread.ReaderImageCache.shared
        val authority = if (playable) null else cacheAuthority()
        val cacheScope = authority?.let { cache.scope(environmentId, threadId, it) }
        val descriptor = attachment.toString()
        return cache.read(cacheScope, descriptor, current = {
            supervisor.value === owner && owner.connection.value === current && (playable || cacheAuthority() == authority)
        }) {
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
            bytes
        }
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

private val attachmentImportLock = kotlinx.coroutines.sync.Mutex()

 data class CheckpointUi(val canRestoreFiles: Boolean = false, val open: Boolean = false, val busy: Boolean = false, val failed: Boolean = false, val preview: String? = null, val record: String? = null)
