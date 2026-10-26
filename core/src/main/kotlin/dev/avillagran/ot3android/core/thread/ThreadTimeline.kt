package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.transport.DomainCalls
import org.json.JSONArray
import org.json.JSONObject

/**
 * A user or assistant row of the lib's `timeline.view`, flattened to a chat bubble until UI-2
 * draws the whole timeline (activity, changes, plans, ...) from [ThreadView.timeline].
 */
data class ThreadMessage(
    val id: String,
    /** `user` or `assistant`. */
    val role: String,
    /** The row's display text; for an assistant row without one, its `blocks` as plain text. */
    val text: String,
    /** Epoch milliseconds (0 when unknown). */
    val createdAt: Long,
    val streaming: Boolean,
    /** The lib's fingerprint of the row: it changes whenever the row must be redrawn. */
    val rev: String?,
)

/**
 * A request waiting for the user, for the banners: an approval (`timeline.view` `approvals`,
 * [kind] its `requestKind`) or a question (`questions`, [kind] `user_input`).
 */
data class RuntimeRequest(val id: String, val kind: String?, val prompt: String?)

/** What the thread screen reads from one `timeline.view`. */
data class ThreadTimeline(
    val messages: List<ThreadMessage>,
    /** `threadState.working`: the lib's active run rule (newest active run, else the thread's). */
    val working: Boolean,
    /** `threadState.activeRunId`, the run Stop interrupts. */
    val activeRunId: String?,
    val pendingRequests: List<RuntimeRequest>,
    /** The whole `timeline.view` result: `timeline`, `threadState`, `tasks`, `queue`, `planView`, ... */
    val raw: JSONObject,
)

/**
 * The thread's derivations, all in the lib: `projection.apply` keeps the held projection
 * current and `timeline.view` turns it into rows. Kotlin only maps the result.
 */
object ThreadTimelines {

    /** The bounded-history fields `timeline.view` echoes, from a `boundedThread` body or stream snapshot. */
    data class History(val cursor: String?, val hasMore: Boolean, val payloadBudgetExceeded: Boolean) {
        companion object {
            val NONE = History(null, hasMore = false, payloadBudgetExceeded = false)

            fun of(snapshot: JSONObject) = History(
                cursor = if (snapshot.isNull("historyCursor")) null else snapshot.opt("historyCursor") as? String,
                hasMore = snapshot.optBoolean("hasMoreHistory"),
                payloadBudgetExceeded = snapshot.optBoolean("payloadBudgetExceeded"),
            )
        }
    }

    /** The result of [apply]: the new projection and how many [events] changed it. */
    data class Applied(val projection: JSONObject, val applied: Int)

    /**
     * `projection.apply` of [events] (`{type, payload}`, as in a stream item's `event`), in
     * order, on [projection]. The lib ignores types it does not know.
     */
    suspend fun apply(domain: DomainCalls, projection: JSONObject, events: List<JSONObject>): Applied {
        val result = domain.domain(
            "projection.apply",
            JSONObject().put("projection", projection).put("events", JSONArray(events)),
        ) as JSONObject
        return Applied(result.getJSONObject("projection"), result.optInt("applied"))
    }

    /** `timeline.view` of [projection]; throws the lib's `malformed` for a projection it cannot read. */
    suspend fun view(domain: DomainCalls, projection: JSONObject, history: History = History.NONE): ThreadTimeline {
        val args = JSONObject().put("projection", projection)
            .put("hasMoreHistory", history.hasMore)
            .put("payloadBudgetExceeded", history.payloadBudgetExceeded)
        history.cursor?.let { args.put("historyCursor", it) }
        return parse(domain.domain("timeline.view", args) as JSONObject)
    }

    fun parse(view: JSONObject): ThreadTimeline {
        val state = view.optJSONObject("threadState") ?: JSONObject()
        val messages = view.objects("timeline").filter { it.optString("role") in MESSAGE_ROLES }.mapNotNull(::message)
        val approvals = view.objects("approvals").map { a ->
            RuntimeRequest(a.optString("requestId"), a.text("requestKind"), a.text("detail"))
        }
        val questions = view.objects("questions").map { q ->
            val first = q.objects("questions").firstOrNull()
            RuntimeRequest(q.optString("requestId"), USER_INPUT, first?.text("question") ?: first?.text("header"))
        }
        return ThreadTimeline(
            messages = messages,
            working = state.optBoolean("working"),
            activeRunId = state.text("activeRunId"),
            pendingRequests = approvals + questions,
            raw = view,
        )
    }

    private fun message(row: JSONObject): ThreadMessage? {
        val text = row.text("text") ?: row.optJSONArray("blocks")?.let(::plainText)?.takeIf { it.isNotBlank() }
        // Assistant rows that only carry an attachment (an HTML render, an MCP app) wait for UI-2.
        text ?: return null
        return ThreadMessage(
            id = row.optString("id"),
            role = row.optString("role"),
            text = text,
            createdAt = row.optLong("createdAt"),
            streaming = row.optBoolean("streaming"),
            rev = row.text("rev"),
        )
    }

    /** Markdown `blocks` as plain text: block texts on their own lines, spans concatenated. */
    fun plainText(blocks: JSONArray): String = buildString {
        for (block in blocks.objects()) {
            val text = when (block.optString("type")) {
                "code" -> block.optString("code").trimEnd('\n')
                "math" -> block.optString("tex")
                "list", "quote" -> {
                    val nested = block.optJSONArray("blocks")?.let(::plainText)
                    nested ?: block.objects("items").joinToString("\n") { item ->
                        item.optJSONArray("blocks")?.let(::plainText).orEmpty()
                    }
                }
                "table" -> block.optString("markdown")
                "image" -> block.optString("alt")
                "rule" -> ""
                else -> spans(block.optJSONArray("spans"))
            }
            if (text.isEmpty()) continue
            if (isNotEmpty()) append('\n')
            append(text)
        }
    }

    private fun spans(spans: JSONArray?): String = buildString {
        for (span in spans?.objects().orEmpty()) {
            when (span.optString("type")) {
                "break" -> append('\n')
                "math" -> append(span.optString("tex"))
                "image" -> append(span.optString("alt"))
                else -> append(span.optString("text"))
            }
        }
    }

    private const val USER_INPUT = "user_input"
    private val MESSAGE_ROLES = setOf("user", "assistant")

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else (opt(key) as? String)?.takeIf { it.isNotEmpty() }

    private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.objects().orEmpty()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
}
