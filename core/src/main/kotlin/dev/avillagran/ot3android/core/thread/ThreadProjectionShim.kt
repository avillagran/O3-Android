// TEMPORARY until lib-t3-rust exposes projection.apply / a reduced thread view (LIB_REQUESTS phase-1
// item 1). It mirrors the lib's `projection::apply` (`src/projection.rs`, v0.3.2) for the rows this
// view reads: `message.updated`, `run.created|updated` (with the lib's `activeRunId` rule),
// `run.background-work-cancelled`, `runtime-request.updated`, and `thread.*` (the payload replaces
// the thread when it names this thread; `thread.pull-request-link-updated` patches one link). Every
// other type the lib reduces is ignored here, and a type the lib does not know only moves the
// cursor, as in the lib and ot3. Nothing here ever asks for a refetch because of an event. Do not
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

    /**
     * The cursor moved (or the item was already applied) and the view did not change: an event
     * the lib reduces into rows this view does not read, a cursor-only item or a marker.
     */
    IGNORED,

    /**
     * An event the lib's `apply` would not apply either (it returns false): a type it does not
     * know, such as `thread.visited`, a `thread.*` naming another thread, or a row without an
     * id. Only the cursor moved.
     */
    UNKNOWN,

    /** A snapshot without `projection` or `snapshotSequence`, or an event with no snapshot to apply to. */
    MALFORMED,
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
     * item, both `{snapshotSequence, projection, ...}`. False, keeping what is held, when
     * either is missing.
     */
    fun replace(snapshot: JSONObject): Boolean {
        val next = snapshot.optJSONObject("projection") ?: return false
        val at = snapshot.long("snapshotSequence") ?: return false
        projection = next
        sequence = at
        return true
    }

    /** Applies one raw thread-stream item, in server order. */
    fun apply(item: JSONObject): ApplyResult = when (item.optString("kind")) {
        // A snapshot always replaces, which also covers `start == "snapshot"` on a resumed stream.
        "snapshot" -> if (replace(item)) ApplyResult.APPLIED else ApplyResult.MALFORMED
        "event" -> {
            val seq = item.long("sequence")
            val held = sequence
            when {
                projection == null -> ApplyResult.MALFORMED
                seq != null && held != null && seq <= held -> ApplyResult.IGNORED
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
        // `synchronized` and any kind added later: nothing to apply (ot3 ignores them too).
        else -> ApplyResult.IGNORED
    }

    /** Applies an `{type, payload}` event to the held projection, as `projection::apply` does. */
    fun applyEvent(event: JSONObject): ApplyResult {
        val p = projection ?: return ApplyResult.MALFORMED
        val type = event.optString("type")
        val payload = event.optJSONObject("payload") ?: return ApplyResult.UNKNOWN
        // Payloads that are not whole rows: the lib applies them to the row they name.
        when (type) {
            "thread.pull-request-link-updated" -> {
                val links = p.optJSONObject("thread")?.optJSONArray("pullRequests") ?: return ApplyResult.UNKNOWN
                for (i in 0 until links.length()) {
                    val link = links.optJSONObject(i) ?: continue
                    if (PULL_REQUEST_KEY.all { sameValue(link.opt(it), payload.opt(it)) }) links.put(i, payload)
                }
                return ApplyResult.APPLIED
            }
            "run.background-work-cancelled" -> {
                val runId = payload.opt("runId")
                p.objects("runs").filter { sameValue(it.opt("id"), runId) }.forEach {
                    it.put("restartCancelledBackgroundWork", payload.opt("restartCancelledBackgroundWork") ?: JSONObject.NULL)
                }
                return ApplyResult.APPLIED
            }
            in IRRELEVANT_PARTIAL -> return ApplyResult.IGNORED
        }
        // Any `thread.*` whose payload is this thread's row replaces it.
        val threadId = p.optJSONObject("thread")?.text("id")
        if (type.startsWith("thread.") && threadId != null && payload.text("id") == threadId) {
            p.put("thread", payload)
            return ApplyResult.APPLIED
        }
        return when (type) {
            "message.updated" -> upsert(p, "messages", payload, cap = null)
            "runtime-request.updated" -> upsert(p, "runtimeRequests", payload, RUNTIME_REQUESTS_CAP)
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
            in IRRELEVANT -> ApplyResult.IGNORED
            // Includes a `thread.*` naming another thread, as in the lib.
            else -> ApplyResult.UNKNOWN
        }
    }

    companion object {
        /**
         * The lib's caps for the lists this shim touches (`src/projection.rs`). Messages are
         * not capped (the lib caps them at 160): the bounded snapshot already decides how much
         * history to send (`payloadBudgetExceeded`), and dropping by position would remove
         * history it painted.
         */
        const val RUNS_CAP = 64
        const val RUNTIME_REQUESTS_CAP = 128

        /**
         * Whole-row events the lib reduces into lists this view does not read
         * (`projection.rs` apply table): the cursor moves, the view does not change.
         */
        val IRRELEVANT = setOf(
            "turn-item.updated", "plan.updated", "run-attempt.created", "run-attempt.updated", "node.updated",
            "subagent.updated", "provider-thread.updated", "provider-turn.updated", "provider-session.attached",
            "provider-session.updated", "checkpoint-scope.created", "checkpoint.captured", "context-handoff.updated",
            "context-transfer.created", "context-transfer.updated",
        )

        /** Partial-payload events the lib applies to rows this view does not read. */
        val IRRELEVANT_PARTIAL = setOf("provider-session.detached", "checkpoint.rollback-requested")

        /** How the lib matches a pull request link to its update. */
        private val PULL_REQUEST_KEY = listOf("host", "repository", "number")

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

        /** The thread has an active run ([activeRunId]); drives the working label and Stop. */
        fun working(projection: JSONObject?): Boolean = activeRunId(projection) != null

        /**
         * The active run as ot3 computes it (`active_run_id`, `src/lib.rs`): the newest
         * `runs[]` row in [ACTIVE_RUN]; only when `runs[]` is empty, `thread.activeRunId`. The
         * bounded snapshot's `thread` carries no `activeRunId` (seen live with a run
         * `running`), so `runs[]` must come first; the lib's apply rule keeps both in step.
         */
        fun activeRunId(projection: JSONObject?): String? {
            val runs = projection.objects("runs")
            if (runs.isEmpty()) return projection?.optJSONObject("thread")?.text("activeRunId")
            return runs.lastOrNull { it.optString("status") in ACTIVE_RUN }?.text("id")
        }

        /** The run Stop interrupts: [activeRunId]. */
        fun interruptibleRunId(projection: JSONObject?): String? = activeRunId(projection)

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

        private fun upsert(p: JSONObject, key: String, row: JSONObject, cap: Int?): ApplyResult {
            val id = row.text("id") ?: return ApplyResult.UNKNOWN
            val list = p.optJSONArray(key) ?: JSONArray().also { p.put(key, it) }
            val index = (0 until list.length()).firstOrNull { list.optJSONObject(it)?.text("id") == id }
            if (index != null) {
                list.put(index, row)
            } else {
                list.put(row)
                // Drop the oldest by position, as the lib does, to keep the mirror bounded.
                if (cap != null && list.length() > cap) {
                    val kept = JSONArray()
                    for (i in list.length() - cap until list.length()) kept.put(list.get(i))
                    p.put(key, kept)
                }
            }
            return ApplyResult.APPLIED
        }

        private fun JSONObject?.objects(key: String): List<JSONObject> {
            val array = this?.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }

        private fun JSONObject.text(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        /** JSON equality of two scalars as serde_json compares them (absent and null are equal). */
        private fun sameValue(a: Any?, b: Any?): Boolean {
            val x = a.takeUnless { it == JSONObject.NULL }
            val y = b.takeUnless { it == JSONObject.NULL }
            return if (x is Number && y is Number) x.toDouble() == y.toDouble() else x == y
        }

        private fun JSONObject.long(key: String): Long? =
            if (isNull(key)) null else (opt(key) as? Number)?.toLong()
    }
}
