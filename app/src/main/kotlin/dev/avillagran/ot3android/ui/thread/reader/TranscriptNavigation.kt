package dev.avillagran.ot3android.ui.thread.reader

import org.json.JSONArray
import org.json.JSONObject

/** A loaded-window search. Never inspects secret descriptors, raw tools or credentials. */
internal data class TranscriptMatch(val rowId: String, val offset: Int)

internal fun transcriptMatches(rows: List<JSONObject>, query: String): List<TranscriptMatch> {
    if (query.isBlank()) return emptyList()
    return buildList {
        rows.filter { it.optString("role") in setOf("user", "assistant", "plan") }.forEach { row ->
            val text = row.optString("text").ifBlank { row.optJSONObject("plan")?.optString("markdown").orEmpty() }
            var start = 0
            while (start <= text.length - query.length) {
                val offset = text.indexOf(query, start, ignoreCase = true)
                if (offset < 0) break
                add(TranscriptMatch(row.getString("id"), offset))
                start = offset + query.length
            }
        }
    }
}

/** JSON null must never become a destination named "null". */
internal fun JSONObject.readerText(key: String): String = if (isNull(key)) "" else optString(key)

internal fun contextEndpoints(boundary: JSONObject): String {
    val from = boundary.optJSONArray("from") ?: JSONArray()
    val endpoints = (0 until from.length()).mapNotNull(from::optJSONObject) + listOfNotNull(boundary.optJSONObject("to"))
    return endpoints.map { endpoint ->
        listOf(endpoint.readerText("instanceId"), endpoint.readerText("model")).filter(String::isNotBlank).joinToString(" / ")
    }.filter(String::isNotBlank).joinToString(" → ")
}

/** Optional P20 integration. A missing callback hides its action. No inert buttons. */
internal class TranscriptActions(
    val secrets: TranscriptSecrets? = null,
    val quote: ((String) -> Unit)? = null,
    val openThread: ((String) -> Unit)? = null,
    val followUpPlan: ((JSONObject, String, Boolean) -> Unit)? = null,
    val planOperation: PlanOperation? = null,
    val planDraft: String = "",
    val fork: ((String) -> Unit)? = null,
)
