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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A message for the snackbar. */
sealed interface ThreadNotice {
    /** The server did not confirm [commandId]; offer to resend it. */
    data class Unconfirmed(val commandId: String) : ThreadNotice

    /** A command failed with the lib's [code]; [rejected] stays on screen until dismissed or resent. */
    data class Failed(val code: String, val reason: String? = null, val requiredScope: String? = null, val rejected: Boolean = false) : ThreadNotice
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
    private val connections: ConnectionManager,
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
    val localEnvironmentId get() = environmentId
    val durableComposerAvailable = attachmentDirectory != null
    private val attachmentKey = java.security.MessageDigest.getInstance("SHA-256").digest((environmentId + "/" + threadId).toByteArray()).joinToString("") { "%02x".format(it) }
    private val attachmentFile = attachmentDirectory?.let { directory ->
        directory.mkdirs()
        android.util.AtomicFile(java.io.File(directory, attachmentKey + ".json"))
    }
    /** Private bytes of the chips that have no host descriptor yet, one file per chip id. */
    private val attachmentSpool = attachmentDirectory?.let { java.io.File(it, attachmentKey + ".spool") }
    private fun spooled(id: String) = attachmentSpool?.let { java.io.File(it, id) }
    /** A camera file is ours to delete, but only once its bytes are safe or the chip is removed. */
    private val attachmentCaptures = mutableMapOf<String, () -> Unit>()
    val attachmentDraft = MutableStateFlow<List<dev.avillagran.ot3android.core.composer.ComposerAttachment>>(emptyList())
    val attachmentStorageError = MutableStateFlow(false)
    val attachmentPending = MutableStateFlow<String?>(null)
    var sharedDraftSynchronizer: dev.avillagran.ot3android.core.composer.SharedDraftSynchronizer? = null
    var sharedDraftRepository: dev.avillagran.ot3android.core.composer.SharedDraftRepository? = null
    private var sharedClear: org.json.JSONObject? = null
    private var attachmentCommand: String? = null
    /** The chips [attachmentCommand] carries; null in older journals, where it carried them all. */
    private var attachmentCommandFiles: Set<String>? = null
    private var attachmentAuthority: String? = null
    val modelOptionsRequest = MutableStateFlow(0)
    val chosenInteraction = MutableStateFlow<String?>(null)
    val planControls = MutableStateFlow(org.json.JSONObject())
    val chatNavigation = MutableStateFlow<String?>(null)
    init {
        attachmentFile?.let { file ->
            try { if (file.baseFile.exists()) {
                val saved = org.json.JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                chosenInteraction.value = saved.optString("interactionMode").takeIf { it == "plan" || it == "default" }
                sharedClear = saved.optJSONObject("sharedClear")
                attachmentAuthority = saved.optString("authority").takeIf { it.isNotEmpty() }
                attachmentCommand = saved.optString("command").takeIf { it.isNotEmpty() }
                attachmentPending.value = attachmentCommand?.let { org.json.JSONObject(it).getString("commandId") }
                attachmentCommandFiles = saved.optJSONArray("commandFiles")?.let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() }
                val files = saved.optJSONArray("attachments") ?: org.json.JSONArray()
                attachmentDraft.value = (0 until files.length()).map { i ->
                    val item = files.getJSONObject(i)
                    val id = item.getString("id")
                    val mime = item.optString("mimeType").takeIf { it.isNotEmpty() }
                    dev.avillagran.ot3android.core.composer.ComposerAttachment(id, item.getString("name"),
                        when {
                            item.has("descriptor") -> dev.avillagran.ot3android.core.composer.UploadStatus.Ready
                            mime != null && spooled(id)?.isFile == true -> dev.avillagran.ot3android.core.composer.UploadStatus.Queued
                            else -> dev.avillagran.ot3android.core.composer.UploadStatus.Failed
                        },
                        item.optString("descriptor").takeIf { it.isNotEmpty() }, mimeType = mime)
                }
                // Only a readable journal can prove a spool file has no chip.
                val chips = attachmentDraft.value.map { it.id }.toSet()
                attachmentSpool?.listFiles()?.filter { it.name !in chips }?.forEach { it.delete() }
            } } catch (_: Exception) { attachmentStorageError.value = true }
        }
    }
    private fun persistAttachments(): Boolean = try {
        val saved = org.json.JSONObject().put("interactionMode", chosenInteraction.value).put("sharedClear", sharedClear).put("authority", attachmentAuthority ?: "").put("command", attachmentCommand ?: "").put("commandFiles", attachmentCommandFiles?.let { org.json.JSONArray(it) }).put("attachments", org.json.JSONArray(attachmentDraft.value.map {
            org.json.JSONObject().put("id", it.id).put("name", it.name).apply {
                it.descriptor?.let { value -> put("descriptor", value) }
                it.mimeType?.let { value -> put("mimeType", value) }
            }
        }))
        attachmentFile?.let { file ->
            val stream = file.startWrite()
            try { stream.write(saved.toString().toByteArray()); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        }
        attachmentStorageError.value = false
        true
    } catch (_: Exception) { attachmentStorageError.value = true; false }
    /** Preserve a corrupt journal for recovery before explicitly replacing it. Text stays in Room. */
    fun repairAttachmentStorage() {
        if (state.value.sending) return
        runCatching {
            attachmentFile?.baseFile?.takeIf { it.exists() }?.let { source ->
                source.copyTo(java.io.File(source.parentFile, source.name + ".recovery-" + System.currentTimeMillis()))
            }
            // A malformed cached command must not survive as an invisible private send lock.
            // Its original bytes are in the backup; Room receipts are retained for decisions.
            if (attachmentCommand != null && runCatching {
                    org.json.JSONObject(attachmentCommand!!).getString("commandId").isNotBlank()
                }.getOrDefault(false).not()) {
                attachmentCommand = null; attachmentCommandFiles = null; attachmentPending.value = null; sharedClear = null
            }
            persistAttachments()
        }
    }

    private suspend fun verifyAttachmentAuthority(required: Boolean = true) {
        val authority = cacheAuthority() ?: if (required) error("attachment_authority_unavailable") else return
        val previous = attachmentAuthority
        check(previous == authority || (attachmentCommand == null && attachmentDraft.value.none { it.descriptor != null })) { "attachment_authority_changed" }
        attachmentAuthority = authority
    }
    fun sharedDraftTargets(): List<dev.avillagran.ot3android.core.composer.SharedDraftTarget> = connections.supervisors.value.flatMap { (local, supervisor) ->
        val server = (supervisor.state.value as? ConnectionState.Connected)?.descriptor?.environmentId
        if (server == null || supervisor.connection.value == null) emptyList() else supervisor.shell.value?.threads.orEmpty().mapNotNull { thread ->
            if (thread.deletedAt != null || thread.archivedAt != null) return@mapNotNull null
            val project = thread.projectId ?: return@mapNotNull null
            dev.avillagran.ot3android.core.composer.SharedDraftTarget(local,
                dev.avillagran.ot3android.core.composer.ComposerScope(server, project, thread.id),
                listOf(local, supervisor.shell.value?.project(project)?.title ?: project, thread.title ?: thread.id).joinToString(" · "))
        }
    }

    /** Cross-host bytes go only through each captured authenticated native connection. */
    suspend fun copySharedAttachments(context: android.content.Context, payload: org.json.JSONObject,
        target: dev.avillagran.ot3android.core.composer.SharedDraftTarget): org.json.JSONObject {
        val source = supervisor.value?.connection?.value ?: error("source_disconnected")
        val sourceScope = state.value.composerScope ?: error("source_unavailable")
        val owner = connections.supervisor(target.localEnvironment) ?: error("target_unavailable")
        val destination = owner.connection.value ?: error("target_disconnected")
        fun validTarget() = owner.connection.value === destination &&
            (owner.state.value as? ConnectionState.Connected)?.descriptor?.environmentId == target.scope.environment &&
            owner.shell.value?.thread(target.scope.thread)?.let { it.projectId == target.scope.project && it.deletedAt == null && it.archivedAt == null } == true
        check(validTarget()) { "target_changed" }
        val files = payload.getJSONArray("attachments")
        require(files.length() <= 16)
        val replaced = org.json.JSONArray()
        var transferredText = payload.getString("text")
        val referenceBuilder = dev.avillagran.ot3android.core.composer.NativeComposer()
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            check(file.getString("sourceEnvironment") == sourceScope.environment) { "source_changed" }
            check(supervisor.value?.connection?.value === source && state.value.composerScope == sourceScope && validTarget())
            val uploaded = if (target.scope.environment == sourceScope.environment) {
                destination.transport.draftAttachment(file.getString("id"), file.getString("name"))
            } else {
                val uploader = destination.transport as? dev.avillagran.ot3android.ui.thread.composer.ComposerBinaryUpload ?: error("upload_unavailable")
                val asset = source.transport.draftAttachmentBytes(file.getString("id"), file.getString("name"))
                try {
                    require(asset.bytes.isNotEmpty() && asset.bytes.size <= 50 * 1024 * 1024)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val temporary = java.io.File.createTempFile("composer-", ".upload", context.cacheDir)
                        try {
                            temporary.outputStream().use { it.write(asset.bytes) }
                            android.os.ParcelFileDescriptor.open(temporary, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                                uploader.upload(fd.fd, file.getString("name"), asset.mimeType, asset.bytes.size.toLong())
                            }
                        } finally { temporary.delete() }
                    }
                } finally { asset.bytes.fill(0) }
            }
            check(supervisor.value?.connection?.value === source && state.value.composerScope == sourceScope && validTarget())
            if (uploaded.getString("id") != file.getString("id")) {
                val original = org.json.JSONObject(uploaded.toString()).put("id", file.getString("id")).put("name", file.getString("name"))
                val oldReference = referenceBuilder.attachmentReference(original)
                val newReference = referenceBuilder.attachmentReference(uploaded)
                transferredText = transferredText.replace(oldReference, newReference)
                // Refuse a noncanonical source reference rather than silently copying an unusable host id.
                check(!transferredText.contains(file.getString("id"))) { "noncanonical_attachment_reference" }
            }
            replaced.put(org.json.JSONObject().put("id", uploaded.getString("id")).put("name", uploaded.getString("name"))
                .put("sourceEnvironment", target.scope.environment))
        }
        check(validTarget())
        return org.json.JSONObject(payload.toString()).put("text", transferredText).put("attachments", replaced).put("selection", org.json.JSONArray().put(transferredText.length).put(transferredText.length))
    }

    /** Metadata conveys no grant. Verify every resource on the captured source host first. */
    suspend fun adoptSharedAttachments(payload: org.json.JSONObject, expectedText: String, applyText: () -> Boolean): Boolean {
        val captured = supervisor.value?.connection?.value ?: return false
        val target = state.value.composerScope ?: return false
        val files = payload.optJSONArray("attachments") ?: org.json.JSONArray()
        if (files.length() > 16 || attachmentPending.value != null || state.value.sending) return false
        val authority = cacheAuthority() ?: return false
        val existing = attachmentDraft.value
        // A chip without a descriptor is local bytes no shared document can describe.
        if (existing.any { it.descriptor == null }) return false
        val same = existing.size == files.length() && existing.indices.all { index ->
            val current = existing[index]
            val descriptor = current.descriptor?.let(::orgJson)
            val metadata = files.getJSONObject(index)
            descriptor?.optString("id") == metadata.optString("id") && descriptor?.optString("name") == metadata.optString("name") &&
                metadata.optString("sourceEnvironment") == target.environment && current.status == dev.avillagran.ot3android.core.composer.UploadStatus.Ready
        }
        val restored = if (same && attachmentAuthority == authority) existing else (0 until files.length()).map { i ->
            val file = files.getJSONObject(i)
            check(file.getString("sourceEnvironment") == target.environment) { "draft_attachment_host" }
            val descriptor = captured.transport.draftAttachment(file.getString("id"), file.getString("name"))
            dev.avillagran.ot3android.core.composer.ComposerAttachment(file.getString("id"), file.getString("name"),
                dev.avillagran.ot3android.core.composer.UploadStatus.Ready, descriptor.toString())
        }
        if (supervisor.value?.connection?.value !== captured || state.value.composerScope != target || state.value.draft != expectedText ||
            attachmentDraft.value != existing || attachmentPending.value != null || state.value.sending) return false
        if (!applyText()) return false
        attachmentAuthority = authority
        attachmentDraft.value = restored
        return persistAttachments()
    }

    fun removeAttachment(id: String) {
        if (local.value.queueBusy || attachmentPending.value != null || state.value.sending) return
        dropAttachments(setOf(id))
        persistAttachments()
    }

    /** The only way a chip's local bytes leave before an upload: the user or its accepted send. */
    private fun dropAttachments(ids: Set<String>) {
        attachmentDraft.value = attachmentDraft.value.filterNot { it.id in ids }
        ids.forEach { id ->
            attachmentCaptures.remove(id)?.invoke()
            spooled(id)?.delete()
        }
    }

    private fun updateAttachment(id: String, change: (dev.avillagran.ot3android.core.composer.ComposerAttachment) -> dev.avillagran.ot3android.core.composer.ComposerAttachment) {
        attachmentDraft.value = attachmentDraft.value.map { if (it.id == id) change(it) else it }
    }

    /**
     * A result lands whatever the connection is doing: returning from a full-screen picker
     * stops the supervisor. The chip appears now, its bytes move to the private spool while the
     * content grant lives, and [uploadQueuedAttachments] sends them. It joins the draft, never a
     * command already in flight.
     */
    fun importAttachment(context: android.content.Context, uri: String) {
        val source = android.net.Uri.parse(uri)
        fun clearCapture() {
            if (source.authority == "${context.packageName}.phone-capture") runCatching { context.contentResolver.delete(source, null, null) }
        }
        val rewinding = checkpoint.value.busy || checkpoint.value.record?.let { org.json.JSONObject(it).optString("state") !in setOf("complete", "failed", "reviewed") } == true
        val spool = attachmentSpool
        if (spool == null || rewinding || attachmentDraft.value.size >= 100) {
            clearCapture()
            local.update { it.copy(notice = ThreadNotice.Failed("attachment_refused")) }
            return
        }
        val id = java.util.UUID.randomUUID().toString()
        attachmentCaptures[id] = ::clearCapture
        attachmentDraft.value += dev.avillagran.ot3android.core.composer.ComposerAttachment(id, "attachment", dev.avillagran.ot3android.core.composer.UploadStatus.Uploading)
        persistAttachments()
        viewModelScope.launch {
            try {
                spool.mkdirs()
                val target = java.io.File(spool, id)
                val saved = dev.avillagran.ot3android.ui.thread.composer.ComposerAttachmentImporter.spool(context, uri, target)
                attachmentCaptures.remove(id)?.invoke()
                if (attachmentDraft.value.none { it.id == id }) target.delete()
                updateAttachment(id) { it.copy(name = saved.name, status = dev.avillagran.ot3android.core.composer.UploadStatus.Queued, mimeType = saved.mimeType) }
            } catch (error: Exception) {
                updateAttachment(id) { it.copy(status = dev.avillagran.ot3android.core.composer.UploadStatus.Failed) }
                if (error is kotlinx.coroutines.CancellationException) throw error
            } finally {
                persistAttachments()
            }
        }
    }

    fun retryAttachment(id: String) {
        updateAttachment(id) { if (it.requeueable) it.copy(status = dev.avillagran.ot3android.core.composer.UploadStatus.Queued) else it }
        persistAttachments()
    }

    private val dev.avillagran.ot3android.core.composer.ComposerAttachment.requeueable: Boolean
        get() = status == dev.avillagran.ot3android.core.composer.UploadStatus.Failed && mimeType != null && spooled(id)?.isFile == true

    /** One file at a time per connection; losing the connection puts the file back in the queue. */
    private suspend fun uploadQueuedAttachments() {
        connection.map { (status, owner) -> owner.takeIf { status is ConnectionState.Connected } }.distinctUntilChanged().collectLatest { live ->
            if (live == null) return@collectLatest
            attachmentDraft.value.filter { it.requeueable }.forEach { retryAttachment(it.id) }
            attachmentDraft.collect {
                // A retry can restore the same Queued value while uploadAttachment is finishing.
                // StateFlow may conflate that transition, so drain the current queue before waiting.
                while (supervisor.value?.connection?.value === live) {
                    val file = attachmentDraft.value.firstOrNull {
                        it.status == dev.avillagran.ot3android.core.composer.UploadStatus.Queued
                    } ?: break
                    uploadAttachment(live, file)
                }
            }
        }
    }

    private suspend fun uploadAttachment(live: Connection, file: dev.avillagran.ot3android.core.composer.ComposerAttachment) {
        val bytes = spooled(file.id) ?: return
        fun current() = supervisor.value?.connection?.value === live
        if (!current()) return
        updateAttachment(file.id) { it.copy(status = dev.avillagran.ot3android.core.composer.UploadStatus.Uploading) }
        try {
            val descriptor = attachmentImportLock.withLock {
                verifyAttachmentAuthority()
                val upload = live.transport as? dev.avillagran.ot3android.ui.thread.composer.ComposerBinaryUpload ?: error("upload_unavailable")
                dev.avillagran.ot3android.ui.thread.composer.ComposerAttachmentImporter.upload(upload, bytes, file.name, file.mimeType ?: "application/octet-stream")
            }
            check(current()) { "connection_changed" }
            bytes.delete()
            updateAttachment(file.id) { it.copy(name = descriptor.optString("name", it.name),
                status = dev.avillagran.ot3android.core.composer.UploadStatus.Ready, descriptor = descriptor.toString()) }
        } catch (cancelled: CancellationException) {
            updateAttachment(file.id) { it.copy(status = dev.avillagran.ot3android.core.composer.UploadStatus.Queued) }
            throw cancelled
        } catch (_: Exception) {
            val status = if (current()) dev.avillagran.ot3android.core.composer.UploadStatus.Failed else dev.avillagran.ot3android.core.composer.UploadStatus.Queued
            updateAttachment(file.id) { it.copy(status = status) }
        } finally {
            persistAttachments()
        }
    }

    fun dispatchAttachments(oppositeOnce: Boolean = false,
        newThread: Boolean = false, plan: org.json.JSONObject? = null) {
        if (attachmentStorageError.value || local.value.queueBusy || state.value.composer.sending || local.value.respondingRequest != null || !state.value.connected || state.value.view.stale || state.value.queueEdit != null) return
        val pending = attachmentCommand
        // Read the owner, not the projection: a send can follow its committing edit in one call.
        val draft = composer.state.value.draft
        if (pending == null && !newThread && plan == null && attachmentDraft.value.isEmpty() &&
            planControls.value.optBoolean("enabled") && draft.trim() in setOf("/plan", "/default")) {
            chooseInteraction(draft.trim().drop(1), clearDraft = draft)
            return
        }
        if (pending == null && (!state.value.unconfirmed.isEmpty() || (plan == null && attachmentDraft.value.isEmpty() && draft.isBlank()) || attachmentDraft.value.any { it.status != dev.avillagran.ot3android.core.composer.UploadStatus.Ready || it.descriptor.isNullOrBlank() })) return
        // Files can land while this command is prepared; they wait for the next one.
        val files = attachmentDraft.value
        local.update { it.copy(queueBusy = true, notice = null) }
        appScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            try {
                // Only uploaded descriptors and a resumed command need this credential to be readable.
                verifyAttachmentAuthority(required = pending != null || files.isNotEmpty())
                var args = pending?.let { org.json.JSONObject(it) } ?: MessageSender.messageCommand("ot3a-" + java.util.UUID.randomUUID(),
                    "ot3a-" + java.util.UUID.randomUUID(), threadId, draft, state.value.modelSelection)
                    .put("attachments", org.json.JSONArray(files.map { org.json.JSONObject(it.descriptor!!) }))
                if (pending == null) {
                    sharedClear = captureSharedClear(args.optString("text"), files)
                    val preferences = clientPreferences?.settings?.first()
                    var delivery = if (state.value.view.activeRunId == null) "auto" else if ((state.value.view.timeline?.optJSONArray("approvals")?.length() ?: 0) > 0) "steer" else sharedDeliveryMode ?: preferences?.optString("followUpBehavior", "queue") ?: "queue"
                    if (oppositeOnce && delivery != "auto") delivery = if (delivery == "queue") "steer" else "queue"
                    args.put("deliveryIntent", delivery)
                    args = prepareChatCommand(args, newThread, plan)
                }
                val id = args.getString("commandId")
                attachmentCommand = args.toString()
                if (pending == null) attachmentCommandFiles = files.map { it.id }.toSet()
                attachmentPending.value = id
                check(persistAttachments()) { "attachment_storage_unavailable" }
                if (outbox.find(id) == null) outbox.add(environmentId, threadId, id, args.toString())
                val result = sender.retry(transport(), id)
                // A fresh command that met no transport was never sent, so it holds no retry identity.
                val unsent = pending == null && result is SendResult.Failed && result.code == MessageSender.NOT_CONNECTED
                if (unsent) sender.discard(id)
                if (result is SendResult.Accepted) {
                    stageSharedClear(args.getString("commandId"))
                    args.optString("acceptedInteractionMode").takeIf { it in setOf("plan", "default") }?.let { chosenInteraction.value = it }
                    dropAttachments(commandAttachments()); attachmentCommand = null; attachmentCommandFiles = null; attachmentPending.value = null
                    persistAttachments()
                    if (composer.state.value.draft == args.optString("text")) composer.onDraftChange("")
                    args.optString("navigateThread").takeIf { it.isNotBlank() }?.let { chatNavigation.value = it }
                } else if (result is SendResult.Failed && (result.rejected || unsent)) {
                    releaseRefused(id, args.optString("text"))
                }
                onResult(result)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onChatError(error)
            } finally { local.update { it.copy(queueBusy = false) } }
        }
    }

    private fun commandAttachments(): Set<String> = attachmentCommandFiles ?: attachmentDraft.value.map { it.id }.toSet()

    /**
     * A definite "no" leaves nothing to reconcile: the editor gets its text back, the chips stay
     * as they are, and the composer is free. The durable draft is written before the lock goes.
     */
    private suspend fun releaseRefused(commandId: String, text: String) {
        composer.restoreRejected(commandId, text)
        attachmentCommand = null; attachmentCommandFiles = null; attachmentPending.value = null; sharedClear = null
        persistAttachments()
    }

    /**
     * The clear command for the authority's copy of this draft, or null. The authority is an
     * optional peer, unrelated to this thread's host: a send never waits for it or fails with
     * it. Without a capture the emptied editor still clears the draft as an ordinary edit.
     * A blocked native call cannot be cancelled, so the capture runs apart from the send.
     */
    private suspend fun captureSharedClear(text: String, files: List<dev.avillagran.ot3android.core.composer.ComposerAttachment>): org.json.JSONObject? {
        val sync = sharedDraftSynchronizer ?: return null
        val source = state.value.composerScope ?: return null
        val capture = appScope.async {
            try {
                val metadata = org.json.JSONArray(files.map { attachment ->
                    val descriptor = org.json.JSONObject(attachment.descriptor!!)
                    org.json.JSONObject().put("id", descriptor.getString("id")).put("name", descriptor.getString("name")).put("sourceEnvironment", source.environment)
                })
                sync.submitted(text, metadata)?.put("authority", sync.repository.authority)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        return withTimeoutOrNull(SHARED_CLEAR_WINDOW_MS) { capture.await() } ?: run { capture.cancel(); null }
    }

    /** The message is already accepted, so bookkeeping for the optional authority cannot fail it. */
    private suspend fun stageSharedClear(commandId: String) {
        val saved = sharedClear ?: return
        val repository = sharedDraftRepository ?: return
        if (saved.optString("authority") != repository.authority) return
        try {
            val command = org.json.JSONObject(saved.toString()).apply { remove("authority") }
            repository.stage(command, command.getString("baseCursor"), identity = "send-$commandId")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
        sharedClear = null
    }

    /** Read fresh provider policy; a cached UI gate never authorizes a command. */
    suspend fun refreshPlanControls(): org.json.JSONObject {
        val live = transport() ?: return org.json.JSONObject().also { planControls.value = it }
        val config = live.call("server.getConfig") as org.json.JSONObject
        val catalog = live.domain("models.catalog", org.json.JSONObject().put("config", config)) as org.json.JSONObject
        val selection = state.value.modelSelection?.let(::orgJson)
        val providers = catalog.optJSONArray("providers") ?: org.json.JSONArray()
        val provider = (0 until providers.length()).map { providers.getJSONObject(it) }
            .firstOrNull { it.optString("instanceId") == selection?.optString("instanceId") }
        val projection = session.projectionSnapshot()
        val thread = projection?.optJSONObject("thread")
        val mode = dev.avillagran.ot3android.core.composer.ChatCommands(live).mode(
            clientPreferences?.settings?.first()?.optBoolean("planModeEnabled") == true,
            provider, chosenInteraction.value ?: thread?.optString("interactionMode")?.takeIf { it == "plan" } ?: "default")
        val access = dev.avillagran.ot3android.core.home.ThreadManagement.access(catalog, live.authSession())
        mode.put("canOperate", access.canWrite && thread?.optBoolean("providerSubagent") != true &&
            thread?.optString("relationshipToParent") != "subagent" &&
            state.value.view.timeline?.optJSONObject("threadState")?.optBoolean("providerSubagent") != true)
        mode.put("providerKnown", provider != null)
        check(transport() === live) { "connection_changed" }
        planControls.value = mode
        return mode
    }

    fun chooseInteraction(requested: String? = null, clearDraft: String? = null) {
        if (!chatWritable()) return
        viewModelScope.launch {
            try {
                val gate = refreshPlanControls()
                if (!chatWritable() || !gate.optBoolean("enabled") || !gate.optBoolean("canOperate")) return@launch
                val previous = chosenInteraction.value
                chosenInteraction.value = requested ?: if (gate.optString("interactionMode") == "plan") "default" else "plan"
                if (!persistAttachments()) { chosenInteraction.value = previous; return@launch }
                if (clearDraft != null && state.value.draft == clearDraft) composer.onDraftChange("")
                refreshPlanControls()
            } catch (e: Exception) { if (e is CancellationException) throw e; onChatError(e) }
        }
    }

    private fun chatWritable(): Boolean = state.value.let {
        it.connected && !it.view.stale && !it.sending && it.queueEdit == null && it.unconfirmed.isEmpty() &&
            attachmentPending.value == null && !attachmentStorageError.value
    }

    private fun onChatError(error: Exception) {
        local.update { it.copy(notice = chatFailure(error)) }
    }

    fun followUpPlan(plan: org.json.JSONObject, newThread: Boolean) {
        if (!chatWritable() || attachmentDraft.value.isNotEmpty()) return
        dispatchAttachments(newThread = newThread, plan = org.json.JSONObject(plan.toString()))
    }

    /** Plan policy is optional metadata for an ordinary message: the host still authorizes it. */
    private suspend fun prepareChatCommand(message: org.json.JSONObject, newThread: Boolean, plan: org.json.JSONObject?): org.json.JSONObject {
        if (plan != null || newThread) return chatSteps(message, newThread, plan)
        if (chosenInteraction.value == null &&
            session.projectionSnapshot()?.optJSONObject("thread")?.optString("interactionMode") != "plan") return message
        return try {
            val gate = refreshPlanControls()
            if (gate.optBoolean("providerKnown") && gate.optBoolean("canOperate")) chatSteps(message, false, null, gate) else message
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message }
    }

    private suspend fun chatSteps(message: org.json.JSONObject, newThread: Boolean, plan: org.json.JSONObject?,
        policy: org.json.JSONObject? = null): org.json.JSONObject {
        val live = transport() ?: error("not_connected")
        val gate = policy ?: refreshPlanControls()
        check(gate.optBoolean("canOperate")) { "permission_denied" }
        val projection = if (plan != null) live.boundedThread(threadId).getJSONObject("projection") else session.projectionSnapshot() ?: error("thread_unavailable")
        val source = projection.getJSONObject("thread")
        val native = dev.avillagran.ot3android.core.composer.NativeComposer(live)
        val originalText = message.getString("text")
        var command = org.json.JSONObject(message.toString())
        var mode = gate.getString("interactionMode")
        if (plan != null) {
            check(gate.optBoolean("enabled") && mode == "plan" && attachmentDraft.value.isEmpty() && !state.value.view.working) { "plan_unavailable" }
            val ready = native.planView(projection).optJSONObject("ready") ?: error("plan_unavailable")
            check(ready.getString("id") == plan.getString("id") && ready.getString("threadId") == threadId && ready.getString("markdown") == plan.getString("markdown")) { "plan_scope_changed" }
            val follow = native.followUp(ready, threadId, if (newThread) "" else message.getString("text"))
            command = native.attachSourceRef(command, follow).put("text", follow.getString("text"))
            mode = follow.getString("interactionMode")
        }
        val steps = org.json.JSONArray()
        val id = message.getString("commandId")
        var destination: String? = null
        if (newThread) {
            val shell = supervisor.value?.shell?.value ?: error("project_unavailable")
            val projectId = source.getString("projectId")
            val project = shell.project(projectId) ?: error("project_unavailable")
            val target = "ot3a-" + java.util.UUID.randomUUID()
            destination = target
            val workspace = org.json.JSONObject().put("type", if (source.isNull("worktreePath")) "root" else "existing_worktree")
            if (!source.isNull("worktreePath")) workspace.put("worktreePath", source.getString("worktreePath"))
            if (!source.isNull("branch")) workspace.put("branch", source.getString("branch"))
            val title = if (plan != null) live.domain("planWorkflow.implementationTitle", org.json.JSONObject().put("markdown", plan.getString("markdown"))) as String
                else state.value.title?.take(255)?.takeIf { it.isNotBlank() } ?: threadId
            val launchSource = org.json.JSONObject(source.toString()).put("modelSelection", state.value.modelSelection?.let(::orgJson))
            // Desktop's explicit new-thread implementation starts in full-access Build mode.
            if (plan != null) launchSource.put("runtimeMode", "full-access")
            val input = org.json.JSONObject().put("environmentId", environmentId).put("projectId", projectId)
                .put("projects", org.json.JSONArray().put(org.json.JSONObject(project.raw.toString()).put("environmentId", environmentId)))
                .put("source", launchSource)
                .put("workspaceStrategy", workspace).put("threadId", target).put("commandId", id + "-launch")
                .put("title", title).put("interactionMode", mode)
            // Native launch delivers an ordinary first message atomically. Plans need the
            // separate native message command because launch's InitialMessage has no sourcePlanRef.
            if (plan == null) input.put("initialMessage", org.json.JSONObject().put("messageId", command.getString("messageId"))
                .put("text", command.getString("text")).put("attachments", command.getJSONArray("attachments")))
            val launch = dev.avillagran.ot3android.core.composer.ChatCommands(live).launch(input)
            steps.put(launch)
            command.put("threadId", target).put("deliveryIntent", "auto")
        } else if (source.optString("interactionMode", "default") != mode) {
            steps.put(dev.avillagran.ot3android.core.composer.ChatCommands.step(
                dev.avillagran.ot3android.core.composer.ChatCommands.interaction(id + "-mode", threadId, mode)))
        }
        if (!newThread || plan != null) steps.put(dev.avillagran.ot3android.core.composer.ChatCommands.step(command))
        check(transport() === live && state.value.draft == originalText) { "composer_changed" }
        if (steps.length() == 1 && plan == null && !newThread) return command
        // Root text is the submitted draft, which can differ from the native implementation prompt.
        return dev.avillagran.ot3android.core.composer.ChatCommands.envelope(id, originalText, steps, destination).put("acceptedInteractionMode", mode)
    }

    fun forkFromResponse(run: String) = conversationMutation(run, false)
    fun mergeBack() = conversationMutation(null, true)

    private fun conversationMutation(requestedRun: String?, merge: Boolean) {
        if (!chatWritable()) return
        local.update { it.copy(queueBusy = true, notice = null) }
        appScope.launch {
            try {
                val gate = refreshPlanControls()
                check(gate.optBoolean("canOperate")) { "permission_denied" }
                val live = transport() ?: error("not_connected")
                val view = state.value.view.timeline ?: error("thread_unavailable")
                val threadState = view.getJSONObject("threadState")
                val run: String
                val target: String
                if (merge) {
                    check(!state.value.view.working) { "thread_is_working" }
                    target = threadState.getString("mergeBackTargetThreadId")
                    run = threadState.getString("mergeBackRunId")
                } else {
                    run = requestedRun ?: error("run_unavailable")
                    val rows = view.optJSONArray("timeline") ?: org.json.JSONArray()
                    check((0 until rows.length()).any { rows.getJSONObject(it).optString("forkRunId") == run }) { "run_unavailable" }
                    target = "ot3a-" + java.util.UUID.randomUUID()
                }
                check(target.isNotBlank() && target != "null" && run.isNotBlank() && run != "null") { "run_unavailable" }
                val id = "ot3a-" + java.util.UUID.randomUUID()
                val command = org.json.JSONObject().put("type", if (merge) "thread.merge_back" else "thread.fork")
                    .put("commandId", id + "-write").put("sourceThreadId", threadId).put("targetThreadId", target)
                    .put("sourcePoint", org.json.JSONObject().put("type", "run").put("runId", run))
                    .put("createdBy", "user").put("creationSource", "web")
                if (!merge) state.value.title?.take(255)?.let { command.put("title", it) }
                val saved = dev.avillagran.ot3android.core.composer.ChatCommands.envelope(id, "",
                    org.json.JSONArray().put(dev.avillagran.ot3android.core.composer.ChatCommands.step(command)), target)
                outbox.add(environmentId, threadId, id, saved.toString())
                val result = sender.retry(live, id)
                if (result is SendResult.Accepted) chatNavigation.value = target
                onResult(result)
            } catch (error: Exception) { if (error is CancellationException) throw error; onChatError(error) }
            finally { local.update { it.copy(queueBusy = false) } }
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
    private data class OlderHistory(val connection: Connection, val rows: org.json.JSONArray, val cursor: String?)
    private val olderHistory = MutableStateFlow<OlderHistory?>(null)
    private var historyJob: Job? = null
    fun loadOlderHistory() {
        if (historyJob?.isActive == true) return
        val current = supervisor.value?.connection?.value ?: return
        val prior = olderHistory.value?.takeIf { it.connection === current }
        val cursor = if (prior != null) prior.cursor else state.value.view.timeline?.optString("historyCursor")?.takeIf { it.isNotBlank() && it != "null" }
        if (cursor == null) return
        val owner = java.util.UUID.randomUUID().toString()
        historyJob = viewModelScope.launch {
            try {
                val page = current.transport.historyPage(threadId, cursor)
                val envelope = current.transport.domain("history.envelope", org.json.JSONObject().put("threadId", threadId).put("page", page)) as org.json.JSONObject
                val presentation = current.transport.domain("timeline.view", org.json.JSONObject().put("projection", envelope.getJSONObject("projection"))) as org.json.JSONObject
                if (supervisor.value?.connection?.value !== current) return@launch
                val merged = current.transport.domain("history.mergeView", org.json.JSONObject()
                    .put("older", prior?.rows ?: org.json.JSONArray()).put("recent", org.json.JSONArray())
                    .put("page", presentation.getJSONArray("timeline")).put("owner", owner).put("pageOwner", owner)
                    .put("cursor", cursor).put("pageCursor", cursor).put("nextCursor", envelope.opt("historyCursor"))
                    .put("hasMoreHistory", envelope.optBoolean("hasMoreHistory"))) as org.json.JSONObject
                if (supervisor.value?.connection?.value === current && merged.getBoolean("applied")) {
                    olderHistory.value = OlderHistory(current, merged.getJSONArray("older"), if (merged.isNull("cursor")) null else merged.getString("cursor"))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { local.update { it.copy(notice = ThreadNotice.Failed("history_unavailable")) } }
        }
    }
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
    }.combine(olderHistory) { ui, older ->
        val recent = ui.view.timeline
        if (older == null || older.connection !== ui.queueConnection || recent == null) ui
        else {
            val merged = older.connection.transport.domain("history.mergeView", org.json.JSONObject()
                .put("older", older.rows).put("recent", recent.optJSONArray("timeline") ?: org.json.JSONArray())
                .put("owner", "current").put("cursor", older.cursor)) as org.json.JSONObject
            val raw = org.json.JSONObject(recent.toString()).put("timeline", merged.getJSONArray("timeline"))
                .put("historyCursor", older.cursor ?: org.json.JSONObject.NULL).put("hasMoreHistory", older.cursor != null)
            val parsed = dev.avillagran.ot3android.core.thread.ThreadTimelines.parse(raw)
            ui.copy(view = ui.view.copy(timeline = raw, messages = parsed.messages))
        }
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
        viewModelScope.launch { uploadQueuedAttachments() }
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
                val permissionViews = org.json.JSONObject()
                val providers = catalog.optJSONArray("providers") ?: org.json.JSONArray()
                for (index in 0 until providers.length()) {
                    val id = providers.getJSONObject(index).getString("instanceId")
                    permissionViews.put(id, live.domain("models.permissionView", org.json.JSONObject().put("catalog", catalog)
                        .put("selection", org.json.JSONObject().put("instanceId", id))))
                }
                catalog.put("permissionViews", permissionViews)
                if (transport() === live) local.update { it.copy(modelCatalog = catalog.toString()) }
            } catch (error: dev.avillagran.t3.T3Exception) {
                local.update { it.copy(notice = ThreadNotice.Failed(error.code, error.reason, error.requiredScope)) }
            } finally {
                local.update { it.copy(modelsLoading = false) }
            }
        }
    }

    suspend fun composerCompletions(snapshot: dev.avillagran.ot3android.core.composer.ComposerSnapshot): Pair<org.json.JSONObject, org.json.JSONArray>? {
        if (!snapshot.transformable || snapshot.scope != state.value.composerScope) return null
        val captured = supervisor.value?.connection?.value ?: return null
        val native = dev.avillagran.ot3android.core.composer.NativeComposer()
        val trigger = native.completion(org.json.JSONObject().put("action", "completion").put("text", snapshot.text)
            .put("cursor", snapshot.selection.end).put("composing", snapshot.composing)) ?: return null
        val shell = supervisor.value?.shell?.value ?: return null
        val thread = shell.thread(threadId) ?: return null
        val cwd = thread.raw.optString("cwd").takeIf { it.isNotBlank() && it != "null" }
            ?: shell.projects.firstOrNull { it.id == thread.projectId }?.workspaceRoot ?: return null
        val rows = if (trigger.getString("kind") == "path") {
            native.paths(captured.transport.call("projects.searchEntries", org.json.JSONObject().put("cwd", cwd)
                .put("query", trigger.getString("query")).put("limit", 40)) as org.json.JSONObject)
        } else {
            val config = captured.transport.call("server.getConfig") as org.json.JSONObject
            val selection = state.value.modelSelection?.let(::orgJson)
            val providers = config.optJSONArray("providers") ?: return null
            val provider = (0 until providers.length()).map { providers.getJSONObject(it) }
                .firstOrNull { it.optString("instanceId") == selection?.optString("instanceId") } ?: return null
            native.catalog(provider, cwd, trigger.getString("kind"), trigger.getString("query"))
        }
        if (supervisor.value?.connection?.value !== captured) return null
        // Model options already have a dedicated real control; do not expose an inert /model row.
        val usable = org.json.JSONArray()
        for (index in 0 until rows.length()) if (rows.getJSONObject(index).optString("insert").isNotEmpty() || rows.getJSONObject(index).optString("kind") == "model") usable.put(rows.getJSONObject(index))
        return trigger to usable
    }
    suspend fun recallableDrafts(): List<String> {
        val messages = state.value.view.timeline?.optJSONArray("timeline") ?: org.json.JSONArray()
        val choices = dev.avillagran.ot3android.core.composer.NativeComposer().recall(messages)
        return (0 until choices.length()).map { choices.getString(it) }
    }

    private var sharedDeliveryMode: String? = null
    fun adoptSharedModel(payload: org.json.JSONObject) {
        payload.optJSONObject("preferences")?.optString("interactionMode")?.takeIf { it in setOf("plan", "default") }?.let {
            chosenInteraction.value = it; persistAttachments()
        }
        sharedDeliveryMode = payload.optJSONObject("preferences")?.optString("deliveryMode")?.takeIf { it in setOf("auto", "queue", "steer") }
        payload.optJSONObject("modelSelection")?.let { model ->
            val provider = model.optString("providerId").takeIf(String::isNotBlank)
            val name = model.optString("modelId").takeIf(String::isNotBlank)
            if (provider != null && name != null) {
                val options = org.json.JSONArray()
                listOf("effort", "reasoningEffort", "speedMode").forEach { key -> if (model.has(key)) options.put(org.json.JSONObject().put("id", key).put("value", model.get(key))) }
                saveModelSelection(org.json.JSONObject().put("instanceId", provider).put("model", name).put("options", options))
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
        if (local.value.selectedModel?.let { dev.avillagran.ot3android.core.composer.SharedDraftRepository.canonical(orgJson(it)) } ==
            dev.avillagran.ot3android.core.composer.SharedDraftRepository.canonical(selection)) return
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
        if (attachmentDraft.value.isNotEmpty() || attachmentPending.value != null) { dispatchAttachments(); return }
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
        appScope.launch {
            val saved = outbox.find(commandId)?.commandJson?.let(::orgJson)
            if (saved?.has(MessageSender.CHAT_STEPS) == true) {
                if (!state.value.connected || state.value.sending) return@launch
                local.update { it.copy(queueBusy = true) }
                try {
                    val result = sender.retry(transport(), commandId)
                    if (result is SendResult.Accepted) saved.optString("navigateThread").takeIf { it.isNotBlank() }?.let { chatNavigation.value = it }
                    if (result is SendResult.Failed && result.rejected) saved.optString("text").takeIf { it.isNotEmpty() }?.let { composer.restoreRejected(commandId, it) }
                    onResult(result)
                } finally { local.update { it.copy(queueBusy = false) } }
            } else composer.retry(commandId)
        }
    }

    /** Explicit recovery: persist all text before removing an uncertain retry identity. Never sends. */
    fun editPending(commandId: String) {
        if (state.value.sending) return
        appScope.launch {
            val command = outbox.find(commandId)?.commandJson ?: attachmentCommand
            val restored = command?.let { runCatching { org.json.JSONObject(it).optString("text") }.getOrNull() }.orEmpty()
            if (!composer.editPending(commandId, restored)) return@launch
            // Retain uploaded descriptors as editable chips; a stale one can be removed.
            if (commandId == attachmentPending.value) {
                attachmentCommand = null; attachmentCommandFiles = null; attachmentPending.value = null; sharedClear = null
                if (!persistAttachments()) return@launch
            }
            sender.discard(commandId)
        }
    }

    fun discard(commandId: String) {
        if (local.value.queueBusy || state.value.composer.sending) return
        if (commandId == attachmentPending.value) {
            dropAttachments(commandAttachments()); attachmentCommand = null; attachmentCommandFiles = null; attachmentPending.value = null; persistAttachments()
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

    private var secretConnection: Connection? = null
    private var secretController: dev.avillagran.ot3android.ui.thread.reader.TranscriptSecrets? = null

    internal fun transcriptSecrets(expected: Connection?): dev.avillagran.ot3android.ui.thread.reader.TranscriptSecrets? {
        if (expected !== secretConnection) {
            secretConnection = expected
            secretController = expected?.let { connection ->
                dev.avillagran.ot3android.ui.thread.reader.scopedTranscriptSecrets(connection.transport, threadId) {
                    isImageConnectionCurrent(connection) && state.value.connected && state.value.view.loaded && !state.value.view.stale
                }
            }
        }
        return secretController.takeIf { expected != null && state.value.connected && state.value.view.loaded && !state.value.view.stale }
    }

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

    private fun failed(result: SendResult.Failed) = ThreadNotice.Failed(result.code, result.reason, result.requiredScope, result.rejected)

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

/** A slow authority must not delay the send by more than this. */
private const val SHARED_CLEAR_WINDOW_MS = 3_000L

 data class CheckpointUi(val canRestoreFiles: Boolean = false, val open: Boolean = false, val busy: Boolean = false, val failed: Boolean = false, val preview: String? = null, val record: String? = null)
