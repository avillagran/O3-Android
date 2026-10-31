package dev.avillagran.ot3android.core.composer

import dev.avillagran.ot3android.core.data.local.SharedDraftJournal
import dev.avillagran.ot3android.core.data.local.SharedDraftJournalDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One explicitly selected authority. All exact commands survive process death before I/O.
 * No timestamps, rebasing, automatic conflict resolution or shared outbox identities.
 */
class SharedDraftRepository(
    val authority: String,
    private val load: suspend () -> String?,
    private val save: suspend (String) -> Unit,
    private val remote: suspend (JSONObject) -> JSONObject,
) {
    constructor(authority: String, dao: SharedDraftJournalDao, remote: suspend (JSONObject) -> JSONObject) : this(
        authority, { dao.get(authority)?.json }, { dao.put(SharedDraftJournal(authority, it)) }, remote,
    )
    private val mutex = synchronized(locks) { locks.getOrPut(authority) { Mutex() } }
    private suspend fun read() = load()?.let(::JSONObject) ?: JSONObject()
        .put("documents", JSONArray()).put("pending", JSONArray()).put("conflicts", JSONArray())
    private suspend fun persist(state: JSONObject) = save(state.toString())
    suspend fun cached(): JSONObject = mutex.withLock { read() }

    /** Persist the exact base and branch even when disconnected. Null expected is create-only. */
    suspend fun stage(command: JSONObject): String = mutex.withLock {
        val state = read()
        val cursor = state.getString("cursor")
        val frozen = JSONObject(command.toString())
        require(frozen.optString("op") in setOf("put", "delete", "transfer"))
        val id = cursor.substringAfterLast(':') + "." + UUID.randomUUID()
        frozen.put("baseCursor", cursor).put("mutationId", id)
        state.getJSONArray("pending").put(frozen)
        persist(state)
        id
    }

    /** Explicit resolution creates a fresh ID; the original conflict remains recoverable. */
    suspend fun resolve(mutationId: String, command: JSONObject): String = mutex.withLock {
        val state = read()
        val conflicts = state.getJSONArray("conflicts")
        require((0 until conflicts.length()).any { conflicts.getJSONObject(it).optString("mutationId") == mutationId })
        val cursor = state.getString("cursor")
        val id = cursor.substringAfterLast(':') + "." + UUID.randomUUID()
        val next = JSONObject(command.toString()).put("baseCursor", cursor).put("mutationId", id)
        require(next.optString("op") in setOf("put", "delete", "transfer"))
        state.getJSONArray("pending").put(next)
        persist(state)
        id
    }

    /** Consume a complete frozen snapshot before replay. On expiry retain EVERY branch,
     * including absent/deleted keys and uncertain creates, as an explicit conflict.
     */
    suspend fun synchronize(): JSONObject = mutex.withLock {
        val state = read()
        var resync = false
        val oldCursor = state.optString("cursor")
        if (oldCursor.isNotEmpty()) {
            val watch = remote(JSONObject().put("op", "watch").put("cursor", oldCursor))
            val error = watch.optString("error")
            resync = error in setOf("draft_resync_required", "draft_snapshot_required")
            check(error.isEmpty() || resync) { "draft_read_failed" }
        }
        var offset: Int? = null
        var cursor: String? = null
        val documents = JSONArray()
        do {
            val request = JSONObject().put("op", "list")
            if (offset != null) request.put("offset", offset).put("snapshot", cursor)
            val page = remote(request)
            check(!page.has("error")) { "draft_snapshot_failed" }
            if (cursor == null) cursor = page.getString("cursor")
            check(cursor == page.getString("cursor")) { "draft_snapshot_changed" }
            val items = page.getJSONArray("items")
            for (i in 0 until items.length()) documents.put(items.get(i))
            offset = if (page.isNull("nextOffset")) null else page.getInt("nextOffset")
        } while (offset != null)
        state.put("documents", documents).put("cursor", cursor)
        val pending = state.getJSONArray("pending")
        if (resync) {
            for (i in 0 until pending.length()) state.getJSONArray("conflicts").put(
                JSONObject().put("mutationId", pending.getJSONObject(i).getString("mutationId"))
                    .put("local", pending.get(i)).put("error", "draft_resync_required"))
            state.put("pending", JSONArray())
            persist(state)
            return@withLock state
        }
        persist(state)
        // Persist removal and receipt after each response. An interrupted call retains its
        // original command and ID, so retry deduplicates without a second write identity.
        while (pending.length() > 0) {
            val command = pending.getJSONObject(0)
            val result = remote(JSONObject(command.toString()))
            if (result.optString("status") == "conflict" || result.optString("error") == "draft_resync_required") {
                state.getJSONArray("conflicts").put(JSONObject().put("mutationId", command.getString("mutationId"))
                    .put("local", command).put("remote", result))
            } else check(result.optString("status") == "applied") { "draft_write_failed" }
            pending.remove(0)
            persist(state)
        }
        state
    }

    companion object {
        private val locks = mutableMapOf<String, Mutex>()
        fun scope(scope: ComposerScope, kind: String = "thread", id: String = scope.thread): JSONObject {
            require(scope.queueMessage == null) { "queue_draft_is_local" }
            return JSONObject().put("environment", scope.environment).put("project", scope.project).put("kind", kind).put("id", id)
        }
    }
}
