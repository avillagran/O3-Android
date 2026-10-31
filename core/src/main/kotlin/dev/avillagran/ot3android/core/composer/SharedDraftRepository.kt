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
    suspend fun stage(command: JSONObject, originalCursor: String? = null, identity: String? = null): String = mutex.withLock {
        val state = read()
        val cursor = originalCursor ?: state.getString("cursor")
        val staged = state.optJSONObject("stagedIdentities") ?: JSONObject().also { state.put("stagedIdentities", it) }
        identity?.let { staged.optString(it).takeIf(String::isNotEmpty)?.let { id -> return@withLock id } }
        val frozen = JSONObject(command.toString())
        require(frozen.optString("op") in setOf("put", "delete", "transfer"))
        frozen.optJSONObject("payload")?.let { require(it.toString().toByteArray(Charsets.UTF_8).size <= 8 * 1024) { "draft_payload_limit" } }
        frozen.optJSONObject("scope")?.let { target ->
            document(state, target)?.takeIf { it.opt("revision") == frozen.opt("expected") }?.let { source ->
                if (!frozen.has("_ot3aSource")) frozen.put("_ot3aSource", JSONObject(source.toString()))
            }
        }
        val id = cursor.substringAfterLast(':') + "." + UUID.randomUUID()
        frozen.put("baseCursor", cursor).put("mutationId", id)
        state.getJSONArray("pending").put(frozen)
        identity?.let { staged.put(it, id) }
        persist(state)
        id
    }

    private fun key(scope: JSONObject) = listOf("environment", "project", "kind", "id").joinToString("/") { scope.getString(it) }
    private fun document(state: JSONObject, scope: JSONObject): JSONObject? {
        val documents = state.getJSONArray("documents")
        return (0 until documents.length()).map { documents.getJSONObject(it) }
            .firstOrNull { key(it.getJSONObject("scope")) == key(scope) }
    }

    /** Persist every local edit before transport; a remote refresh cannot move its CAS base. */
    suspend fun edit(scope: ComposerScope, payload: JSONObject) = mutex.withLock {
        require(payload.toString().toByteArray(Charsets.UTF_8).size <= 8 * 1024) { "draft_payload_limit" }
        val target = scope(scope)
        val state = read()
        val editors = state.optJSONObject("editors") ?: JSONObject().also { state.put("editors", it) }
        val branch = editors.optJSONObject(key(target)) ?: JSONObject().put("scope", target)
            .put("expected", JSONObject.NULL)
            .put("baseCursor", state.optString("cursor")).also { editors.put(key(target), it) }
        val pending = state.getJSONArray("pending")
        val clearing = (0 until pending.length()).any { i -> pending.getJSONObject(i).let {
            it.optString("op") == "delete" && key(it.getJSONObject("scope")) == key(target)
        } }
        if (clearing && payload.optString("text").isEmpty() && payload.getJSONArray("attachments").length() == 0) {
            branch.put("payload", payload).put("dirty", false)
            persist(state)
            return@withLock
        }
        val currentDocument = document(state, target)
        if (!branch.optBoolean("dirty") && currentDocument?.optBoolean("deleted") == true &&
            branch.opt("expected") == currentDocument.opt("revision") && payload.optString("text").isEmpty() && payload.getJSONArray("attachments").length() == 0) {
            branch.put("payload", payload)
            persist(state)
            return@withLock
        }
        if (branch.optJSONObject("payload")?.let(::contentCanonical) == contentCanonical(payload)) return@withLock
        branch.put("payload", JSONObject(payload.toString())).put("dirty", true)
        persist(state)
    }

    /** Only clean editors follow remote changes. Dirty/uncertain branches never auto-rebase. */
    suspend fun follow(scope: ComposerScope, commit: Boolean = true): JSONObject? = mutex.withLock {
        val target = scope(scope)
        val state = read()
        val editors = state.optJSONObject("editors") ?: JSONObject().also { state.put("editors", it) }
        val branch = editors.optJSONObject(key(target))
        val conflicts = state.getJSONArray("conflicts")
        val unresolved = (0 until conflicts.length()).any { i ->
            conflicts.getJSONObject(i).optJSONObject("local")?.optJSONObject("scope")?.let { key(it) == key(target) } == true
        }
        if (unresolved || branch?.optBoolean("dirty") == true || branch?.optBoolean("blocked") == true) return@withLock null
        val doc = document(state, target) ?: return@withLock null
        if (commit) {
            editors.put(key(target), JSONObject().put("scope", target).put("expected", doc.get("revision"))
                .put("baseCursor", state.getString("cursor")).put("dirty", false).put("payload", doc.optJSONObject("payload") ?: JSONObject()))
            persist(state)
        }
        JSONObject(doc.toString()).put("_cursor", state.getString("cursor"))
    }

    /** The UI commits exactly the document it actually adopted, never a newer snapshot. */
    suspend fun adopted(scope: ComposerScope, document: JSONObject) = mutex.withLock {
        val state = read()
        val target = scope(scope)
        val editors = state.optJSONObject("editors") ?: JSONObject().also { state.put("editors", it) }
        val branch = editors.optJSONObject(key(target))
        if (branch?.optBoolean("dirty") == true || branch?.optBoolean("blocked") == true) return@withLock
        editors.put(key(target), JSONObject().put("scope", target).put("expected", document.get("revision"))
            .put("baseCursor", document.getString("_cursor")).put("payload", document.optJSONObject("payload") ?: JSONObject()).put("dirty", false))
        persist(state)
    }

    /** Capture this before send. The acknowledgement must never clear a newer remote revision. */
    suspend fun submitted(scope: ComposerScope, text: String, payload: JSONObject? = null): JSONObject? = mutex.withLock {
        val state = read()
        val doc = document(state, scope(scope)) ?: return@withLock null
        if (doc.optBoolean("deleted") || doc.getJSONObject("payload").optString("text") != text ||
            (payload != null && contentCanonical(doc.getJSONObject("payload")) != contentCanonical(payload))) return@withLock null
        JSONObject().put("op", "delete").put("scope", doc.getJSONObject("scope"))
            .put("expected", doc.get("revision")).put("baseCursor", state.getString("cursor"))
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
        for (i in 0 until conflicts.length()) if (conflicts.getJSONObject(i).optString("mutationId") == mutationId) {
            val resolved = conflicts.remove(i) as JSONObject
            val target = resolved.getJSONObject("local").getJSONObject("scope")
            document(state, target)?.let { resolved.put("remoteBranch", JSONObject(it.toString())) }
            if (key(target) != key(next.getJSONObject("scope"))) state.optJSONObject("editors")?.optJSONObject(key(target))?.put("blocked", false)?.put("dirty", false)
            state.optJSONObject("editors")?.optJSONObject(key(target))?.let { resolved.put("editorBranch", JSONObject(it.toString())) }
            val archive = state.optJSONArray("resolved") ?: JSONArray().also { state.put("resolved", it) }
            archive.put(resolved)
            break
        }
        next.optJSONObject("scope")?.let { target -> state.optJSONObject("editors")?.optJSONObject(key(target))?.put("blocked", true) }
        persist(state)
        id
    }

    /** Keep the remote branch after explicit review; archive the local command for recovery. */
    suspend fun keepRemote(mutationId: String) = mutex.withLock {
        val state = read()
        val conflicts = state.getJSONArray("conflicts")
        val index = (0 until conflicts.length()).first { conflicts.getJSONObject(it).getString("mutationId") == mutationId }
        val archived = conflicts.remove(index) as JSONObject
        val localScope = archived.getJSONObject("local").getJSONObject("scope")
        state.optJSONObject("editors")?.optJSONObject(key(localScope))?.let { archived.put("editorBranch", JSONObject(it.toString())) }
        val resolved = state.optJSONArray("resolved") ?: JSONArray().also { state.put("resolved", it) }
        resolved.put(archived)
        val target = archived.getJSONObject("local").getJSONObject("scope")
        state.optJSONObject("editors")?.optJSONObject(key(target))?.put("dirty", false)?.put("blocked", false)
        persist(state)
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
        val editors = state.optJSONObject("editors") ?: JSONObject()
        for (editorKey in editors.keys()) {
            val branch = editors.getJSONObject(editorKey)
            val queued = state.getJSONArray("pending")
            val hasPending = (0 until queued.length()).any { queued.getJSONObject(it).optString("editorKey") == editorKey }
            if (branch.optBoolean("dirty") && !branch.optBoolean("blocked") && !hasPending) {
                val base = branch.optString("baseCursor").ifEmpty { cursor!! }
                val command = JSONObject().put("op", "put").put("scope", branch.getJSONObject("scope"))
                    .put("expected", branch.get("expected")).put("payload", branch.getJSONObject("payload"))
                    .put("baseCursor", base).put("mutationId", base.substringAfterLast(':') + "." + UUID.randomUUID())
                    .put("editorKey", editorKey)
                queued.put(command)
            }
        }
        val pending = state.getJSONArray("pending")
        if (resync) {
            for (i in 0 until pending.length()) state.getJSONArray("conflicts").put(
                JSONObject().put("mutationId", pending.getJSONObject(i).getString("mutationId"))
                    .put("local", pending.get(i)).put("error", "draft_resync_required"))
            state.put("pending", JSONArray())
            for (editorKey in editors.keys()) if (editors.getJSONObject(editorKey).optBoolean("dirty")) editors.getJSONObject(editorKey).put("blocked", true)
            persist(state)
            return@withLock state
        }
        persist(state)
        // Persist removal and receipt after each response. An interrupted call retains its
        // original command and ID, so retry deduplicates without a second write identity.
        while (pending.length() > 0) {
            val command = pending.getJSONObject(0)
            val wire = JSONObject(command.toString()).apply { remove("editorKey"); remove("_ot3aSource") }
            val result = remote(wire)
            val branch = editors.optJSONObject(command.optString("editorKey"))
            if (result.optString("status") == "conflict" || result.optString("error") == "draft_resync_required") {
                branch?.put("blocked", true)
                state.getJSONArray("conflicts").put(JSONObject().put("mutationId", command.getString("mutationId"))
                    .put("local", command).put("remote", result))
            } else {
                check(result.optString("status") == "applied") { "draft_write_failed" }
                val event = result.optJSONObject("event")
                if (branch != null && event != null) {
                    branch.put("expected", event.get("revision"))
                        .put("baseCursor", cursor!!.substringBeforeLast(':') + ":" + event.getLong("sequence"))
                        .put("dirty", canonical(branch.getJSONObject("payload")) != canonical(command.getJSONObject("payload")))
                }
                // Explicit resolution remains archived, but its editor may now follow the chosen result.
                if (branch == null) command.optJSONObject("scope")?.let { target ->
                    editors.optJSONObject(key(target))?.takeIf { it.optBoolean("blocked") }?.put("blocked", false)?.put("dirty", false)
                }
            }
            pending.remove(0)
            persist(state)
        }
        state
    }

    companion object {
        fun contentCanonical(payload: JSONObject): String = canonical(JSONObject(payload.toString()).apply { remove("selection") })

        /** Compare wire data structurally; Rust and Android preserve different object key order. */
        fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            null, JSONObject.NULL -> "null"
            else -> value.toString()
        }
        private val locks = mutableMapOf<String, Mutex>()
        fun scope(scope: ComposerScope, kind: String = "thread", id: String = scope.thread): JSONObject {
            require(scope.queueMessage == null) { "queue_draft_is_local" }
            return JSONObject().put("environment", scope.environment).put("project", scope.project).put("kind", kind).put("id", id)
        }
    }
}
