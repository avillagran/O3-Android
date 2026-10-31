package dev.avillagran.ot3android.core.providers

import org.json.JSONObject
import org.json.JSONArray

/** UI-only matching over the already normalized native catalog. */
object ModelPresentation {
    fun objects(array: JSONArray?): List<JSONObject> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }

    fun search(row: JSONObject, query: String): Int {
        val text = listOf("displayName", "name", "model", "providerName", "subProvider")
            .joinToString(" ") { row.optString(it) }.lowercase(java.util.Locale.ROOT)
        var score = 0
        for (word in query.trim().lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)) {
            val at = text.indexOf(word)
            if (at >= 0) { score += 500 - at; continue }
            var position = 0
            for (letter in text) if (position < word.length && word[position] == letter) position++
            if (position != word.length) return -1
            score += 100
        }
        return score
    }

    fun value(descriptor: JSONObject, selection: JSONObject): Any? =
        objects(selection.optJSONArray("options")).firstOrNull { it.optString("id") == descriptor.optString("id") }
            ?.opt("value") ?: descriptor.opt("currentValue")

    fun available(descriptor: JSONObject, value: Any?): Boolean = when (descriptor.optString("type")) {
        "boolean" -> value is Boolean
        "select" -> objects(descriptor.optJSONArray("options")).any { it.opt("id") == value }
        else -> false
    }
}
