package dev.avillagran.ot3android.core.thread

import org.json.JSONArray
import org.json.JSONObject

/** Thread projections and stream items shaped like the live server's (docs/PROTOCOL_NOTES.md). */
object ThreadFixtures {

    fun message(id: String, text: String, createdAt: String, role: String = "assistant", streaming: Boolean = false): JSONObject =
        JSONObject().put("id", id).put("role", role).put("text", text).put("attachments", JSONArray())
            .put("createdAt", createdAt).put("runId", "run-1").put("streaming", streaming)

    fun run(id: String, status: String): JSONObject = JSONObject().put("id", id).put("status", status)

    fun projection(
        threadId: String = "t1",
        messages: List<JSONObject> = emptyList(),
        runs: List<JSONObject> = emptyList(),
        activeRunId: String? = null,
        runtimeRequests: List<JSONObject> = emptyList(),
        turnItems: List<JSONObject> = emptyList(),
    ): JSONObject = JSONObject()
        .put(
            "thread",
            JSONObject().put("id", threadId).put("title", "Thread $threadId").put("status", "idle")
                .put("activeRunId", activeRunId ?: JSONObject.NULL)
                .put("modelSelection", JSONObject().put("instanceId", "codex").put("model", "gpt-5")),
        )
        .put("messages", JSONArray(messages))
        .put("runs", JSONArray(runs))
        .put("runtimeRequests", JSONArray(runtimeRequests))
        .put("turnItems", JSONArray(turnItems))

    /** The `boundedThread` body. */
    fun bounded(sequence: Long, projection: JSONObject): JSONObject = JSONObject()
        .put("snapshotSequence", sequence).put("projection", projection)
        .put("historyCursor", JSONObject.NULL).put("hasMoreHistory", false)

    fun snapshotItem(sequence: Long, projection: JSONObject): JSONObject =
        bounded(sequence, projection).put("kind", "snapshot")

    fun event(sequence: Long, type: String, payload: JSONObject): JSONObject = JSONObject()
        .put("kind", "event").put("sequence", sequence)
        .put("event", JSONObject().put("type", type).put("payload", payload))

    fun unknownEvent(sequence: Long): JSONObject = JSONObject().put("kind", "unknown-event").put("sequence", sequence)
}

/** Key names; android.jar's org.json (compiled against) has no `keySet`. */
fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

/** JSON equality regardless of key order; android.jar's org.json has no `similar`. */
fun sameJson(a: Any?, b: Any?): Boolean = when {
    a is JSONObject && b is JSONObject -> a.keyNames() == b.keyNames() && a.keyNames().all { sameJson(a.opt(it), b.opt(it)) }
    a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { sameJson(a.opt(it), b.opt(it)) }
    else -> a == b
}
