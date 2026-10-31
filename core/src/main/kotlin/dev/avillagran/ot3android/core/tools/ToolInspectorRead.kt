package dev.avillagran.ot3android.core.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** The viewer identity, distinct from the source thread of an inherited item. */
data class ToolInspectorOwner(val environment: String, val threadId: String)

/** Static native projections only. Stream reduction must come from lib U36. */
object ToolInspectorRead {
    private val referenceKeys = listOf("sourceThreadId", "sourceItemId", "type", "runId", "providerTurnId", "revision")

    suspend fun read(reference: JSONObject, owner: ToolInspectorOwner?, reader: suspend (JSONObject) -> JSONObject?): JSONObject? {
        // Freeze the caller's mutable JSON before suspending.
        val frozen = JSONObject(reference.toString())
        val response = reader(JSONObject(frozen.toString()))
        currentCoroutineContext().ensureActive()
        if (response == null) return null
        val scope = response.optJSONObject("scope")
        // Legacy callers already validate in toolInspector.parse. Explicit owners additionally
        // reject a response from a replaced host/viewer, including missing scope.
        if (owner != null) {
            require(scope != null && scope.optString("environment") == owner.environment &&
                scope.optString("threadId") == owner.threadId) { "tool_scope_mismatch" }
        }
        if (scope != null) require(referenceKeys.all { scope.opt(it) == frozen.opt(it) }) { "tool_scope_mismatch" }
        return response
    }

    data class DisplayText(val text: String, val truncated: Boolean)

    /** Bound rendered/copied static text, preserving Unicode scalar boundaries. No VT reducer. */
    fun display(text: String, limit: Int = 64 * 1024): DisplayText {
        require(limit >= 0)
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val code = text.codePointAt(end)
            val width = when { code <= 0x7f -> 1; code <= 0x7ff -> 2; code <= 0xffff -> 3; else -> 4 }
            if (bytes + width > limit) break
            bytes += width
            end += Character.charCount(code)
        }
        return DisplayText(if (end == text.length) text else text.substring(0, end), end < text.length)
    }
}
