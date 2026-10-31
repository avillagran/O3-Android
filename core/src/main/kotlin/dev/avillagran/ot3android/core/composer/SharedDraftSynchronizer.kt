package dev.avillagran.ot3android.core.composer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Process-lifetime worker. Local edits and exact commands survive transport failure in Room. */
class SharedDraftSynchronizer(
    val repository: SharedDraftRepository,
    private val scope: ComposerScope,
    worker: CoroutineScope,
    private val syncRemotely: Boolean = true,
    private val persistRemote: suspend (String) -> Unit = {},
) {
    data class Update(val generation: Long, val document: String, val observation: Long = 0)
    private data class Edit(val generation: Long, val payload: String)
    private val edits = Channel<Edit>(Channel.CONFLATED)
    private val mutableRemote = MutableStateFlow<Update?>(null)
    val remote = mutableRemote.asStateFlow()
    private val mutableFailed = MutableStateFlow(false)
    val failed = mutableFailed.asStateFlow()
    private var paused = false
    fun pause(value: Boolean) { if (paused != value) { paused = value; generation++ } }
    private val mutableConflicts = MutableStateFlow(0)
    val conflicts = mutableConflicts.asStateFlow()
    private var generation = 0L
    private var latest: String? = null
    private var persisted: String? = null
    private var savedRemoteRevision: String? = null
    private var acceptedRevision: String? = null

    /** Re-entering a clean editor must adopt remote metadata before publishing stale local options. */
    fun bind(payload: JSONObject) {
        val remote = mutableRemote.value
        val document = remote?.document?.let(::JSONObject)
        val text = if (document?.optBoolean("deleted") == true) "" else document?.optJSONObject("payload")?.optString("text")
        if (remote != null && isCurrent(remote) && text == payload.optString("text")) {
            acceptedRevision = null
            mutableRemote.value = remote.copy(observation = remote.observation + 1)
        } else edit(payload)
    }

    fun edit(payload: JSONObject) {
        val value = SharedDraftRepository.canonical(payload)
        if (latest?.let(::JSONObject)?.let(SharedDraftRepository::contentCanonical) == SharedDraftRepository.contentCanonical(payload)) {
            latest = value
            return
        }
        acceptedRevision = null
        latest = value
        edits.trySend(Edit(++generation, value))
    }

    private val job: Job
    fun stop() { job.cancel() }
    init {
        job = worker.launch {
            var observation = 0L
            var waiting: Edit? = edits.receive()
            while (isActive) {
                val edit = if (waiting != null) waiting else withTimeoutOrNull(2_000) { edits.receive() }
                waiting = edit
                try {
                    if (edit != null && edit.payload != persisted) {
                        // Empty initial editors may follow the authority; local legacy text is create-only.
                        if (persisted != null || JSONObject(edit.payload).optString("text").isNotEmpty() ||
                            JSONObject(edit.payload).getJSONArray("attachments").length() > 0) {
                            repository.edit(scope, JSONObject(edit.payload))
                        }
                        persisted = edit.payload
                    }
                    waiting = null
                    val captured = edit?.generation ?: generation
                    if (syncRemotely) {
                        repository.synchronize()
                        // Fetch the receipt's new document before exposing it to the editor.
                        repository.synchronize()
                    }
                    mutableConflicts.value = repository.cached().getJSONArray("conflicts").length()
                    val document = if (!paused && generation == captured) repository.follow(scope, commit = false) else null
                    if (document != null && generation == captured && document.getString("revision") != acceptedRevision) {
                        val payload = if (document.optBoolean("deleted")) JSONObject().put("schema", 1).put("text", "").put("attachments", org.json.JSONArray())
                            else document.getJSONObject("payload")
                        if (document.getString("revision") != savedRemoteRevision) {
                            persistRemote(payload.optString("text"))
                            savedRemoteRevision = document.getString("revision")
                        }
                        if (generation != captured) continue
                        persisted = SharedDraftRepository.canonical(payload)
                        latest = persisted
                        mutableRemote.value = Update(captured, document.toString(), ++observation)
                    }
                    mutableFailed.value = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableFailed.value = true }
            }
        }
    }

    suspend fun submitted(text: String, attachments: org.json.JSONArray? = null): JSONObject? {
        val payload = latest?.let(::JSONObject) ?: return null
        if (payload.optString("text") != text) return null
        if (attachments != null) payload.put("attachments", attachments)
        repository.edit(scope, payload)
        repository.synchronize()
        repository.synchronize()
        return repository.submitted(scope, text, payload)
    }

    suspend fun adopted(update: Update) {
        if (isCurrent(update)) {
            val document = JSONObject(update.document)
            repository.adopted(scope, document)
            if (isCurrent(update)) acceptedRevision = document.getString("revision")
        }
    }

    fun isCurrent(update: Update) = update.generation == generation
}
