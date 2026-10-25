// TEMPORARY until lib-t3-rust exposes projection.apply / a reduced thread view (LIB_REQUESTS phase-1
// item 1). It applies only the event types below, mirroring the lib's `projection::apply`
// (`src/projection.rs`, v0.3.2: same per-list caps, same `activeRunId` rule); any other event type
// is not guessed at: the view is marked stale and the caller refetches the bounded snapshot. Do not
// grow it: new event types go into the lib, and this file is deleted when the lib ships item 1.
package dev.avillagran.ot3android.core.thread

import org.json.JSONArray
import org.json.JSONObject

/** A row of the projection's `messages[]`; streaming rows repeat whole with a growing [text]. */
data class ThreadMessage(
    val id: String,
    /** `user` or `assistant`. */
    val role: String,
    val text: String,
    val createdAt: String?,
    val streaming: Boolean,
    val runId: String?,
)

/** A `runtimeRequests[]` row still `pending`: an approval or a question waits for the user. */
data class RuntimeRequest(val id: String, val kind: String?, val prompt: String?)

/** What [ThreadProjectionShim.apply] did with one stream item. */
enum class ApplyResult {
    /** The projection changed. */
    APPLIED,

    /** Nothing to do: already applied, a cursor-only item, or a payload the lib also skips. */
    IGNORED,

    /** An event this shim does not apply: the view is stale until a fresh snapshot replaces it. */
    REFETCH,
}

/**
 * The projection of one thread, held as the server's JSON. Not thread-safe: one owner applies
 * items in order. [sequence] is where the thread stream resumes.
 */
class ThreadProjectionShim {

    /** `snapshotSequence` of the held snapshot, or the last event applied or skipped after it. */
    var sequence: Long? = null
        private set

    var projection: JSONObject? = null
        private set

    val hasSnapshot: Boolean get() = projection != null

    /**
     * Replaces everything with a snapshot: the `boundedThread` body or a stream `snapshot`
     * item, both `{snapshotSequence, projection, ...}`. False when it carries no projection.
     */
    fun replace(snapshot: JSONObject): Boolean {
        val next = snapshot.optJSONObject("projection") ?: return false
        projection = next
        sequence = snapshot.long("snapshotSequence")
        return true
    }

    /** Applies one raw thread-stream item, in server order. */
    fun apply(item: JSONObject): ApplyResult = when (item.optString("kind")) {
        // A snapshot always replaces, which also covers `start == "snapshot"` on a resumed stream.
        "snapshot" -> if (replace(item)) ApplyResult.APPLIED else ApplyResult.REFETCH
        "event" -> {
            val seq = item.long("sequence")
            when {
                projection == null -> ApplyResult.REFETCH
                seq != null && sequence != null && seq <= sequence!! -> ApplyResult.IGNORED
                else -> {
                    if (seq != null) sequence = seq
                    applyEvent(item.optJSONObject("event") ?: JSONObject())
                }
            }
        }
        // Events this build cannot decode still move the resume cursor.
        "unknown-event" -> {
            item.long("sequence")?.let { seq -> sequence = maxOf(sequence ?: seq, seq) }
            ApplyResult.IGNORED
        }
        "synchronized" -> ApplyResult.IGNORED
        else -> ApplyResult.REFETCH
    }

    /** Applies an `{type, payload}` event to the held projection. */
    fun applyEvent(event: JSONObject): ApplyResult {
        val p = projection ?: return ApplyResult.REFETCH
        val type = event.optString("type")
        val payload = event.optJSONObject("payload") ?: return ApplyResult.IGNORED
        return when (type) {
            "message.created", "message.updated" -> upsert(p, "messages", payload, MESSAGES_CAP)
            "message.deleted" -> {
                val id = payload.text("id") ?: payload.text("messageId") ?: return ApplyResult.REFETCH
                remove(p, "messages", id)
            }
            "run.created", "run.updated" -> {
                val result = upsert(p, "runs", payload, RUNS_CAP)
                if (result == ApplyResult.APPLIED) {
                    val thread = p.optJSONObject("thread") ?: JSONObject().also { p.put("thread", it) }
                    if (payload.optString("status") in ACTIVE_RUN) {
                        thread.put("activeRunId", payload.get("id"))
                    } else if (thread.text("activeRunId") == payload.text("id")) {
                        thread.put("activeRunId", JSONObject.NULL)
                    }
                }
                result
            }
            // Like the lib: the payload is the whole row and replaces it, only for this thread.
            "thread.updated" -> {
                val id = payload.text("id")
                if (id != null && id == p.optJSONObject("thread")?.text("id")) {
                    p.put("thread", payload)
                    ApplyResult.APPLIED
                } else {
                    ApplyResult.IGNORED
                }
            }
            else -> ApplyResult.REFETCH
        }
    }

    companion object {
        /** The lib's caps for the lists this shim touches (`src/projection.rs`). */
        const val MESSAGES_CAP = 160
        const val RUNS_CAP = 64

        val ACTIVE_RUN = setOf("preparing", "starting", "running", "waiting")

        /** `messages[]` as rows, ordered by `createdAt` then array order. */
        fun messages(projection: JSONObject?): List<ThreadMessage> =
            projection.objects("messages").mapNotNull { m ->
                val id = m.text("id") ?: return@mapNotNull null
                ThreadMessage(
                    id = id,
                    role = m.text("role") ?: "assistant",
                    text = m.text("text").orEmpty(),
                    createdAt = m.text("createdAt"),
                    streaming = m.optBoolean("streaming"),
                    runId = m.text("runId"),
                )
            }.sortedBy { it.createdAt.orEmpty() }

        /** A run is preparing, starting, running or waiting, or the thread names an active run. */
        fun working(projection: JSONObject?): Boolean =
            activeRunId(projection) != null ||
                projection.objects("runs").any { it.optString("status") in ACTIVE_RUN }

        fun activeRunId(projection: JSONObject?): String? = projection?.optJSONObject("thread")?.text("activeRunId")

        /** The run to interrupt: the thread's active run, else the newest active one. */
        fun interruptibleRunId(projection: JSONObject?): String? = activeRunId(projection)
            ?: projection.objects("runs").lastOrNull { it.optString("status") in ACTIVE_RUN }?.text("id")

        fun title(projection: JSONObject?): String? = projection?.optJSONObject("thread")?.text("title")

        fun modelSelection(projection: JSONObject?): JSONObject? =
            projection?.optJSONObject("thread")?.optJSONObject("modelSelection")

        /**
         * Pending runtime requests, with the prompt of the request or of its latest turn item
         * (read-only here; answering them comes with the lib's approvals and questions).
         */
        fun pendingRequests(projection: JSONObject?): List<RuntimeRequest> {
            val items = projection.objects("turnItems")
            return projection.objects("runtimeRequests").filter { it.optString("status") == "pending" }.mapNotNull { r ->
                val id = r.text("id") ?: return@mapNotNull null
                val prompt = r.text("prompt") ?: items.lastOrNull { it.text("requestId") == id }?.text("prompt")
                RuntimeRequest(id, r.text("kind"), prompt)
            }
        }

        private fun upsert(p: JSONObject, key: String, row: JSONObject, cap: Int): ApplyResult {
            val id = row.text("id") ?: return ApplyResult.IGNORED
            val list = p.optJSONArray(key) ?: JSONArray().also { p.put(key, it) }
            val index = (0 until list.length()).firstOrNull { list.optJSONObject(it)?.text("id") == id }
            if (index != null) {
                list.put(index, row)
            } else {
                list.put(row)
                // Drop the oldest by position, as the lib does, to keep the mirror bounded.
                if (list.length() > cap) {
                    val kept = JSONArray()
                    for (i in list.length() - cap until list.length()) kept.put(list.get(i))
                    p.put(key, kept)
                }
            }
            return ApplyResult.APPLIED
        }

        private fun remove(p: JSONObject, key: String, id: String): ApplyResult {
            val list = p.optJSONArray(key) ?: return ApplyResult.IGNORED
            val index = (0 until list.length()).firstOrNull { list.optJSONObject(it)?.text("id") == id }
                ?: return ApplyResult.IGNORED
            list.remove(index)
            return ApplyResult.APPLIED
        }

        private fun JSONObject?.objects(key: String): List<JSONObject> {
            val array = this?.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }

        private fun JSONObject.text(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        private fun JSONObject.long(key: String): Long? =
            if (isNull(key)) null else (opt(key) as? Number)?.toLong()
    }
}
