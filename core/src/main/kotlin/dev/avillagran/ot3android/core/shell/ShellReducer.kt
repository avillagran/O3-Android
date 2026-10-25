// TEMPORARY until lib-t3-rust exposes a shell reducer through `domain` (`shell.apply`,
// docs/LIB_REQUESTS.md "2026-10-25: v0.3.3", finding 1). v0.3.3 ships the sidebar
// (`sidebar.view`, see SidebarView.kt) but still expects the host to keep the shell stream's
// state. This file is a minimal port of ot3's `src/shell_state.rs` (omarchy-t3-code v0.2.22),
// itself following T3's client-runtime `state/shellReducer.ts`. Do not grow it: new shell
// behaviour goes into the lib, and this file is deleted when the lib ships the reducer.
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
    /**
     * The project row as last received, for fields the reducer does not read
     * (`defaultModelSelection`, `scripts`, ...). Read only; [repositoryIdentity] wins over it.
     */
    val raw: JSONObject,
)

/**
 * `pendingRuntimeRequest`: the thread may wait for the user (approval, input, ...).
 * [blocking] is null when the server did not say; only `false` means it does not hold the thread.
 */
data class PendingRequest(val id: String?, val kind: String?, val blocking: Boolean?)

/**
 * A thread row of the shell, reduced to what the sidebar reads. Every field is optional on
 * the wire. Timestamps are epoch milliseconds, read from ISO-8601 text (T3 writes
 * `toISOString()`) or an epoch-millisecond number; one that is present but unreadable is 0,
 * so "is it set" still holds.
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
    val settledAt: Long?,
    /** `"settled"`, `"active"` or null; a boolean `true` on the wire becomes `"settled"`. */
    val settledOverride: String?,
    val unsettledAt: Long?,
    val pinnedAt: Long?,
    val pinOrderKey: String?,
    /** Manual order of an active, unpinned row (a fractional index: compare as text). */
    val activeOrderKey: String?,
    val archivedAt: Long?,
    val deletedAt: Long?,
    val createdAt: Long?,
    val updatedAt: Long?,
    val latestRunRequestedAt: Long?,
    val latestRunCompletedAt: Long?,
    val latestUserMessageAt: Long?,
    val parentThreadId: String?,
    val relationshipToParent: String?,
    val branch: String?,
    /**
     * The thread row as last received (snapshot or `thread.updated`), for fields the reducer
     * does not read (`modelSelection`, `runtimeMode`, `interactionMode`,
     * `providerInstanceId`, `latestRunId`, ...). Read only. Compared by reference, so two
     * parses of the same row are not equal.
     */
    val raw: JSONObject,
)

/**
 * The shell of one environment. [sequence] is the `snapshotSequence` of the last
 * authoritative snapshot or applied event: the shell stream resumes after it.
 */
data class ShellState(
    val sequence: Long?,
    val projects: List<ShellProject>,
    val threads: List<ShellThread>,
) {
    private val threadsById by lazy(LazyThreadSafetyMode.PUBLICATION) { threads.associateBy { it.id } }
    private val projectsById by lazy(LazyThreadSafetyMode.PUBLICATION) { projects.associateBy { it.id } }

    /** The thread row [id] (archived and subagent ones included), or null. */
    fun thread(id: String): ShellThread? = threadsById[id]

    /** The project [id], or null. */
    fun project(id: String): ShellProject? = projectsById[id]
}

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
            val previous = shell.project(next.id)
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
        return next.copy(projects = next.projects.map { p -> retainIdentity(state.project(p.id), p) })
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
            raw = json,
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
                PendingRequest(it.text("id"), it.text("kind"), it.opt("blocking") as? Boolean)
            },
            lastError = json.text("lastError"),
            settledAt = json.timestamp("settledAt"),
            settledOverride = when (val override = json.opt("settledOverride")) {
                true -> "settled"
                is String -> override
                else -> null
            },
            unsettledAt = json.timestamp("unsettledAt"),
            pinnedAt = json.timestamp("pinnedAt"),
            pinOrderKey = json.text("pinOrderKey"),
            activeOrderKey = json.text("activeOrderKey"),
            archivedAt = json.timestamp("archivedAt"),
            deletedAt = json.timestamp("deletedAt"),
            createdAt = json.timestamp("createdAt"),
            updatedAt = json.timestamp("updatedAt"),
            latestRunRequestedAt = json.timestamp("latestRunRequestedAt"),
            latestRunCompletedAt = json.timestamp("latestRunCompletedAt"),
            latestUserMessageAt = json.timestamp("latestUserMessageAt"),
            parentThreadId = lineage?.text("parentThreadId"),
            relationshipToParent = lineage?.text("relationshipToParent"),
            branch = json.text("branch"),
            raw = json,
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

    private fun JSONObject.timestamp(key: String): Long? = when (val value = if (isNull(key)) null else opt(key)) {
        null -> null
        is Number -> value.toLong()
        else -> parseTimestamp(value.toString()) ?: 0L
    }

    private val ISO_8601 = Regex(
        """(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d+))?)?\s*(Z|z|[+-]\d{2}(?::?\d{2})?)?""",
    )

    /**
     * Epoch milliseconds of an ISO-8601 date-time (`2026-10-24T10:00:00.000Z`, any fraction,
     * `Z` or an offset; none means UTC), or null. By hand because `java.time` needs API 26.
     */
    fun parseTimestamp(text: String): Long? {
        val match = ISO_8601.matchEntire(text.trim()) ?: return null
        val g = match.groupValues
        val (year, month, day) = Triple(g[1].toInt(), g[2].toInt(), g[3].toInt())
        if (month !in 1..12 || day !in 1..31) return null
        val seconds = g[6].ifEmpty { "0" }.toInt()
        val millis = g[7].take(3).padEnd(3, '0').toInt()
        val offsetMinutes = when (val zone = g[8]) {
            "", "Z", "z" -> 0
            else -> {
                val digits = zone.drop(1).replace(":", "")
                val minutes = digits.take(2).toInt() * 60 + digits.drop(2).ifEmpty { "0" }.toInt()
                if (zone[0] == '-') -minutes else minutes
            }
        }
        // Days from 1970-01-01 in the proleptic Gregorian calendar (H. Hinnant's days_from_civil).
        val y = if (month <= 2) year - 1 else year
        val era = y.floorDiv(400)
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        val days = era * 146_097L + dayOfEra - 719_468
        val secondsOfDay = g[4].toInt() * 3600L + g[5].toInt() * 60 + seconds - offsetMinutes * 60L
        return (days * 86_400 + secondsOfDay) * 1000 + millis
    }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
}
