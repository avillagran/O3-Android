package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Batch
import dev.avillagran.t3.ThreadUpdate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds [ThreadUpdate]s over a minimal [T3Batch], for [FakeT3Transport] scripts and for the
 * closed update the native transport emits when a poll fails. Items are split the way
 * `ThreadStream.poll` splits them: the last `snapshot` item is [ThreadUpdate.snapshot], the
 * items after it (except `synchronized`) are [ThreadUpdate.events]. Every batch ends a chunk.
 */
object ThreadUpdates {

    /** One poll carrying [items] in server order. */
    fun batch(
        vararg items: JSONObject,
        cursor: Long? = null,
        start: String? = null,
        idle: Boolean = false,
        completionMarker: Boolean = false,
        closed: Boolean = false,
        error: String? = null,
        errorMessage: String? = null,
    ): ThreadUpdate {
        var snapshot: JSONObject? = null
        val events = mutableListOf<JSONObject>()
        var synchronized = false
        for (item in items) {
            when (item.optString("kind")) {
                "snapshot" -> { snapshot = item; events.clear() }
                "synchronized" -> synchronized = true
                else -> events.add(item)
            }
        }
        val boundaries = if (items.isEmpty()) emptyList() else listOf(items.size)
        val batch = T3Batch(
            items = JSONArray(items.toList()), closed = closed, error = error, errorMessage = errorMessage,
            boundaries = boundaries, idle = idle, completionMarker = completionMarker, cursor = cursor, start = start,
        )
        return ThreadUpdate(
            snapshot = snapshot, events = events, repaint = boundaries.isNotEmpty() || idle,
            synchronized = synchronized, cursor = cursor, start = start, idle = idle,
            completionMarker = completionMarker, closed = closed, error = error, errorMessage = errorMessage,
            batch = batch,
        )
    }

    /** A snapshot item (`kind` is set to `snapshot`) that replaces the held state. */
    fun snapshot(item: JSONObject, cursor: Long? = null): ThreadUpdate =
        batch(item.put("kind", "snapshot"), cursor = cursor, start = "snapshot")

    /** Event items to apply on top of the held state. */
    fun event(vararg items: JSONObject, cursor: Long? = null): ThreadUpdate = batch(*items, cursor = cursor)

    /** Caught up for now: no items. */
    fun idle(cursor: Long? = null): ThreadUpdate = batch(cursor = cursor, idle = true)

    /** The last update of a stream: [error] is a stable code, null after a cancel. */
    fun closed(
        error: String?,
        cursor: Long? = null,
        errorMessage: String? = null,
        start: String? = null,
    ): ThreadUpdate = batch(cursor = cursor, start = start, closed = true, error = error, errorMessage = errorMessage)
}
