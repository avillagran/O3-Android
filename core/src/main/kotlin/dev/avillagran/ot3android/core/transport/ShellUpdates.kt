package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.ThreadUpdate
import org.json.JSONObject

// Stopgap for docs/LIB_REQUESTS.md, "2026-10-25: v0.3.2 through the app's transport layer",
// finding 1: T3 follows the shell snapshot with a metadata-only `snapshot` item (it carries
// `resolvedRepositoryIdentityRoots`), which lib-t3-rust 0.3.2 treats as a full shell. Every
// shell consumer reads shell updates through these functions instead of
// `ThreadUpdate.snapshot`, `events`, `start` and `cursor`. This is protocol knowledge in
// Kotlin, accepted only until a lib release fixes the finding; then this file goes away.

private const val METADATA_KEY = "resolvedRepositoryIdentityRoots"

private fun JSONObject.isSnapshot() = optString("kind") == "snapshot"

private fun JSONObject.isShellMetadata() = isSnapshot() && has(METADATA_KEY)

private fun JSONObject.isAuthoritativeSnapshot() = isSnapshot() && !has(METADATA_KEY)

private fun ThreadUpdate.items(): List<JSONObject> =
    List(batch.items.length()) { batch.items.optJSONObject(it) }.filterNotNull()

/**
 * The authoritative shell snapshot of this update (the whole `snapshot` item, as
 * `ThreadUpdate.snapshot` would be), or null when the update has none. Read from
 * `batch.items`, so a metadata item later in the same batch cannot shadow it. When it is
 * not null, it replaces the held shell, then [shellEvents] apply on top.
 */
fun ThreadUpdate.shellSnapshot(): JSONObject? = items().lastOrNull { it.isAuthoritativeSnapshot() }

/**
 * True when every `snapshot` item of this update is a metadata refresh (and there is at
 * least one). Then the held shell stays: do not replace it, ignore `start` (the lib says
 * `"snapshot"` for it, also on a resume), do not adopt `cursor` (use [shellCursor]), merge
 * [shellMetadata] into the project identities and apply [shellEvents].
 */
fun ThreadUpdate.isShellMetadataOnly(): Boolean {
    val snapshots = items().filter { it.isSnapshot() }
    return snapshots.isNotEmpty() && snapshots.all { it.isShellMetadata() }
}

/** The metadata-only `snapshot` items of this update, in server order. */
fun ThreadUpdate.shellMetadata(): List<JSONObject> = items().filter { it.isShellMetadata() }

/**
 * The shell items to apply, in server order: every item after the last authoritative
 * snapshot (all of them when there is none), without `snapshot` and `synchronized` items.
 * Unlike `ThreadUpdate.events`, this keeps the events that came before a metadata item.
 */
fun ThreadUpdate.shellEvents(): List<JSONObject> {
    val items = items()
    val from = items.indexOfLast { it.isAuthoritativeSnapshot() } + 1
    return items.drop(from).filter { !it.isSnapshot() && it.optString("kind") != "synchronized" }
}

/**
 * Where to resume the shell after this update, by the lib's rule without the metadata item:
 * an authoritative snapshot sets the cursor to its `snapshot.snapshotSequence`, other items
 * only move it on to their `sequence`, metadata items leave it alone. [previous] is what
 * this returned for the previous update (or the `afterSequence` the stream was opened
 * with); pass the result as `afterSequence` when resuming, instead of `ThreadUpdate.cursor`.
 */
fun ThreadUpdate.shellCursor(previous: Long?): Long? = items().fold(previous) { cursor, item ->
    when {
        item.isShellMetadata() -> cursor
        item.isSnapshot() -> item.optJSONObject("snapshot")?.optLongOrNull("snapshotSequence") ?: cursor
        else -> item.optLongOrNull("sequence")?.let { maxOf(cursor ?: it, it) } ?: cursor
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
