package dev.avillagran.ot3android.core.composer

/** Stable server identity, never a display name, local connection id or checkout path. */
data class ComposerScope(val environment: String, val project: String, val thread: String, val queueMessage: String? = null)
data class SharedDraftTarget(val localEnvironment: String, val scope: ComposerScope, val label: String)
data class ComposerSelection(val start: Int, val end: Int)
data class ComposerAttachment(
    val id: String,
    val name: String,
    val status: UploadStatus,
    /** Native validated descriptor serialized unchanged; never a content:// URI. */
    val descriptor: String? = null,
    val error: String? = null,
)
enum class UploadStatus { Uploading, Ready, Failed }
enum class ComposerDelivery { Default, OppositeOnce, Background, SendAndNew }
enum class ComposerInteraction { Build, Plan }

/** A native-projected reference keeps UTF-16 source positions; Kotlin never parses Markdown. */
data class ComposerReference(val label: String, val start: Int, val end: Int)
data class ComposerCompletion(val id: String, val label: String, val description: String = "")
data class ProjectComposerDraft(val id: String, val revision: String, val preview: String, val attachmentCount: Int)

/** Snapshot tokens are local edit identities, not server revisions or wall clock timestamps. */
data class ComposerSnapshot(
    val scope: ComposerScope,
    val version: Long = 0,
    val text: String = "",
    val selection: ComposerSelection = ComposerSelection(0, 0),
    val attachments: List<ComposerAttachment> = emptyList(),
    val composing: Boolean = false,
    val loading: Boolean = false,
    val sending: Boolean = false,
    val uncertainCommand: String? = null,
    val sendBlocked: Boolean = false,
    val interaction: ComposerInteraction = ComposerInteraction.Build,
) {
    val editable: Boolean get() = !loading && !sending && uncertainCommand == null
    val transformable: Boolean get() = editable && !composing && attachments.none { it.status == UploadStatus.Uploading }
    val sendable: Boolean get() = !sendBlocked && transformable && attachments.all { it.status == UploadStatus.Ready && !it.descriptor.isNullOrBlank() } &&
        (text.isNotBlank() || attachments.isNotEmpty())
}

/**
 * Ephemeral edit buffer, deliberately NOT a local replacement for shared drafts. The owner
 * publishes [snapshot] after each action. Async results must carry the captured scope and
 * version; late responses cannot alter another thread, a queue edit, or newer typing.
 * Calls belong to one UI dispatcher. Persistence belongs to the P20/server adapter.
 */
class ComposerSession(initial: ComposerSnapshot) {
    var snapshot: ComposerSnapshot = initial
        private set
    private val past = ArrayDeque<ComposerSnapshot>()
    private val future = ArrayDeque<ComposerSnapshot>()

    fun edit(text: String, selection: ComposerSelection, composing: Boolean): Boolean {
        if (!snapshot.editable || !validRange(text, selection)) return false
        if (text == snapshot.text && selection == snapshot.selection && composing == snapshot.composing) return false
        val changedText = text != snapshot.text
        if (changedText && !snapshot.composing) remember()
        snapshot = snapshot.copy(text = text, selection = selection, composing = composing, version = snapshot.version + 1)
        return true
    }

    /** Atomic native edit/history/quick-reply result. Reject a stale range, even in the same thread. */
    fun replace(expected: ComposerSnapshot, text: String, selection: ComposerSelection): Boolean {
        if (!matches(expected) || !snapshot.transformable || !validRange(text, selection)) return false
        remember()
        snapshot = snapshot.copy(text = text, selection = selection, version = snapshot.version + 1)
        return true
    }

    fun undo(redo: Boolean = false): Boolean {
        if (!snapshot.transformable) return false
        val from = if (redo) future else past
        val to = if (redo) past else future
        if (from.isEmpty()) return false
        to.addLast(snapshot)
        val previous = from.removeLast()
        // Uploads are independent of text undo. Never resurrect a removed descriptor/upload.
        snapshot = snapshot.copy(text = previous.text, selection = previous.selection, composing = false, version = snapshot.version + 1)
        return true
    }

    fun beginUpload(expected: ComposerSnapshot, id: String, name: String): Boolean {
        if (!matches(expected) || !snapshot.editable || snapshot.attachments.size >= 100 || snapshot.attachments.any { it.id == id }) return false
        snapshot = snapshot.copy(attachments = snapshot.attachments + ComposerAttachment(id, name, UploadStatus.Uploading), version = snapshot.version + 1)
        return true
    }

    /** Completion may coexist with typing, but cannot resurrect a removed/retried file. */
    fun finishUpload(scope: ComposerScope, id: String, descriptor: String?, error: String?): Boolean {
        if (snapshot.scope != scope || !snapshot.editable) return false
        val file = snapshot.attachments.find { it.id == id && it.status == UploadStatus.Uploading } ?: return false
        val ready = !descriptor.isNullOrBlank() && error == null
        val finished = file.copy(status = if (ready) UploadStatus.Ready else UploadStatus.Failed,
            descriptor = if (ready) descriptor else null, error = if (ready) null else error ?: "upload_failed")
        snapshot = snapshot.copy(attachments = snapshot.attachments.map { if (it.id == id) finished else it }, version = snapshot.version + 1)
        return true
    }

    fun removeAttachment(id: String): Boolean {
        if (!snapshot.editable || snapshot.attachments.none { it.id == id }) return false
        snapshot = snapshot.copy(attachments = snapshot.attachments.filterNot { it.id == id }, version = snapshot.version + 1)
        return true
    }

    /** Called only with an authoritative durable-outbox transition; unknown never means accepted. */
    fun commandState(sending: Boolean, uncertainCommand: String?) {
        snapshot = snapshot.copy(sending = sending, uncertainCommand = uncertainCommand, composing = false, version = snapshot.version + 1)
    }

    private fun matches(expected: ComposerSnapshot) = snapshot.scope == expected.scope && snapshot.version == expected.version
    private fun remember() {
        past.addLast(snapshot.copy(attachments = emptyList()))
        if (past.size > 100) past.removeFirst()
        future.clear()
    }
    private fun validRange(text: String, range: ComposerSelection): Boolean = range.start in 0..text.length && range.end in 0..text.length &&
        !splitsSurrogate(text, range.start) && !splitsSurrogate(text, range.end)
    private fun splitsSurrogate(text: String, offset: Int): Boolean = offset > 0 && offset < text.length &&
        Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])
}
