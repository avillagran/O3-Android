// TEMPORARY until lib-t3-rust exposes the shell reducer (`shell.reduce`, docs/LIB_REQUESTS.md
// phase 1 item 3). This file is a minimal port of ot3's `src/shell_state.rs` (omarchy-t3-code
// v0.2.22), itself following T3's client-runtime `state/shellReducer.ts`. Do not grow it: new
// shell behaviour goes into the lib, and this file is deleted when the lib ships item 3.
package dev.avillagran.ot3android.core.shell

import dev.avillagran.ot3android.core.transport.isShellMetadataOnly
import dev.avillagran.ot3android.core.transport.shellEvents
import dev.avillagran.ot3android.core.transport.shellMetadata
import dev.avillagran.ot3android.core.transport.shellSnapshot
import dev.avillagran.t3.ThreadUpdate
import org.json.JSONObject

/** A project row of the shell, reduced to what the sidebar reads. */
data class ShellProject(
    val id: String,
    val title: String?,
    val workspaceRoot: String?,
    /** The server's `repositoryIdentity`, kept as compact JSON text; null while unresolved. */
    val repositoryIdentity: String?,
)

/** `pendingRuntimeRequest`: the thread waits for the user (approval, input, ...). */
data class PendingRequest(val id: String?, val kind: String?, val blocking: Boolean)

/**
 * A thread row of the shell, reduced to what the sidebar reads. Every field is optional on
 * the wire. Timestamps stay ISO-8601 strings as sent (T3 writes `toISOString()`, UTC with
 * milliseconds), so they order correctly as text.
 */
data class ShellThread(
    val id: String,
    val projectId: String?,
    val title: String?,
    /** Open set: idle, running, completed, failed, cancelled, interrupted, ... */
    val status: String?,
    val activeRunId: String?,
    val pendingRuntimeRequest: PendingRequest?,
    val lastError: String?,
    val settledAt: String?,
    /** `"settled"`, `"active"` or null; a boolean `true` on the wire becomes `"settled"`. */
    val settledOverride: String?,
    val pinnedAt: String?,
    val pinOrderKey: String?,
    val archivedAt: String?,
    val deletedAt: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val parentThreadId: String?,
    val relationshipToParent: String?,
    val branch: String?,
)

/**
 * The shell of one environment. [sequence] is the `snapshotSequence` of the last
 * authoritative snapshot or applied event: the shell stream resumes after it.
 */
data class ShellState(
    val sequence: Long?,
    val projects: List<ShellProject>,
    val threads: List<ShellThread>,
)

object ShellReducer {

    /** A shell body: the HTTP shell response or a stream snapshot's `snapshot` object. */
    fun parse(body: JSONObject): ShellState = ShellState(
        sequence = body.long("snapshotSequence"),
        projects = body.objects("projects").mapNotNull(::project),
        // archivedThreads has its own bounded query; the sidebar never shows it.
        threads = body.objects("threads").mapNotNull(::thread),
    )

    /**
     * Applies one shell stream update. Read through the `ShellUpdates.kt` adapter (the
     * stopgap for LIB_REQUESTS.md "v0.3.2 through the app's transport layer", finding 1): the
     * authoritative snapshot, if any, replaces [state]; metadata-only snapshots only merge
     * project identities; then the events apply in server order.
     */
    fun reduce(state: ShellState?, update: ThreadUpdate): ShellState? {
        var current = update.shellSnapshot()?.let { replace(state, it) } ?: state
        for (metadata in update.shellMetadata()) current = mergeIdentities(current, metadata)
        for (event in update.shellEvents()) current = applyEvent(current, event)
        return current
    }

    /**
     * Applies one stream event and returns the new state (the same instance when nothing
     * applies). Events wait for a snapshot and are ignored at or below [ShellState.sequence].
     */
    fun applyEvent(state: ShellState?, item: JSONObject): ShellState? = when (item.optString("kind")) {
        "project.updated" -> event(state, item) { shell ->
            val next = item.optJSONObject("project")?.let(::project) ?: return@event shell
            val previous = shell.projects.firstOrNull { it.id == next.id }
            shell.copy(projects = shell.projects.upsert(retainIdentity(previous, next)) { it.id })
        }
        "project.removed" -> event(state, item) { shell ->
            val id = item.text("projectId")
            shell.copy(projects = shell.projects.filterNot { it.id == id })
        }
        "thread.updated" -> event(state, item) { shell ->
            val next = item.optJSONObject("thread")?.let(::thread) ?: return@event shell
            // Older servers still send archive-located deltas on this stream: those remove.
            val threads = if ((item.text("location") ?: "active") == "active") {
                shell.threads.upsert(next) { it.id }
            } else {
                shell.threads.filterNot { it.id == next.id }
            }
            shell.copy(threads = threads)
        }
        "thread.removed" -> event(state, item) { shell ->
            val id = item.text("threadId")
            shell.copy(threads = shell.threads.filterNot { it.id == id })
        }
        else -> state
    }

    /** An authoritative `snapshot` item replaces the shell, keeping resolved identities. */
    private fun replace(state: ShellState?, item: JSONObject): ShellState? {
        val body = item.optJSONObject("snapshot") ?: return state
        val next = parse(body)
        if (state == null) return next
        val merged = next.copy(
            projects = next.projects.map { p -> retainIdentity(state.projects.firstOrNull { it.id == p.id }, p) },
        )
        return if (merged == state) state else merged
    }

    /**
     * A metadata-only snapshot ([isShellMetadataOnly]): its `threads` is empty and its
     * sequence does not move the cursor ("a resume prefix must not skip replayed events").
     * It only refreshes the identities of the projects named in its
     * `resolvedRepositoryIdentityRoots`, or fills ones still unresolved.
     */
    private fun mergeIdentities(state: ShellState?, item: JSONObject): ShellState? {
        state ?: return null
        val body = item.optJSONObject("snapshot") ?: return state
        val roots = item.optJSONArray("resolvedRepositoryIdentityRoots")
        val resolved = (0 until (roots?.length() ?: 0)).mapNotNull { roots?.optString(it, null) }.toSet()
        val candidates = body.objects("projects").mapNotNull(::project).associateBy { it.id }
        var changed = false
        val projects = state.projects.map { project ->
            val candidate = candidates[project.id]
            if (candidate == null || candidate.workspaceRoot != project.workspaceRoot) return@map project
            val isResolved = project.workspaceRoot in resolved
            val fills = project.repositoryIdentity == null && candidate.repositoryIdentity != null
            if ((isResolved || fills) && project.repositoryIdentity != candidate.repositoryIdentity) {
                changed = true
                project.copy(repositoryIdentity = candidate.repositoryIdentity)
            } else {
                project
            }
        }
        return if (changed) state.copy(projects = projects) else state
    }

    private inline fun event(state: ShellState?, item: JSONObject, change: (ShellState) -> ShellState): ShellState? {
        state ?: return null
        val sequence = item.long("sequence") ?: return state
        if (sequence <= (state.sequence ?: 0)) return state
        return change(state).copy(sequence = sequence)
    }

    /** Enrichment is asynchronous: keep a resolved identity while the workspace root is the same. */
    private fun retainIdentity(previous: ShellProject?, next: ShellProject): ShellProject =
        if (previous != null && next.repositoryIdentity == null && previous.repositoryIdentity != null &&
            previous.workspaceRoot == next.workspaceRoot
        ) {
            next.copy(repositoryIdentity = previous.repositoryIdentity)
        } else {
            next
        }

    private fun project(json: JSONObject): ShellProject? {
        val id = json.text("id") ?: return null
        return ShellProject(
            id = id,
            title = json.text("title"),
            workspaceRoot = json.text("workspaceRoot"),
            repositoryIdentity = json.opt("repositoryIdentity").takeUnless { it == null || it == JSONObject.NULL }
                ?.toString(),
        )
    }

    private fun thread(json: JSONObject): ShellThread? {
        val id = json.text("id") ?: return null
        val pending = json.optJSONObject("pendingRuntimeRequest")
        val lineage = json.optJSONObject("lineage")
        return ShellThread(
            id = id,
            projectId = json.text("projectId"),
            title = json.text("title"),
            status = json.text("status"),
            activeRunId = json.text("activeRunId"),
            pendingRuntimeRequest = pending?.let {
                PendingRequest(it.text("id"), it.text("kind"), it.optBoolean("blocking"))
            },
            lastError = json.text("lastError"),
            settledAt = json.text("settledAt"),
            settledOverride = when (val override = json.opt("settledOverride")) {
                true -> "settled"
                is String -> override
                else -> null
            },
            pinnedAt = json.text("pinnedAt"),
            pinOrderKey = json.text("pinOrderKey"),
            archivedAt = json.text("archivedAt"),
            deletedAt = json.text("deletedAt"),
            createdAt = json.text("createdAt"),
            updatedAt = json.text("updatedAt"),
            parentThreadId = lineage?.text("parentThreadId"),
            relationshipToParent = lineage?.text("relationshipToParent"),
            branch = json.text("branch"),
        )
    }

    private fun <T> List<T>.upsert(item: T, id: (T) -> String): List<T> {
        val index = indexOfFirst { id(it) == id(item) }
        return if (index < 0) this + item else toMutableList().also { it[index] = item }
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else opt(key)?.toString()

    private fun JSONObject.long(key: String): Long? =
        if (isNull(key)) null else (opt(key) as? Number)?.toLong()

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
}
