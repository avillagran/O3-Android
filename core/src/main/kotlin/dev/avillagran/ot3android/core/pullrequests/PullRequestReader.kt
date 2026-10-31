package dev.avillagran.ot3android.core.pullrequests

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.T3Transport
import org.json.JSONArray
import org.json.JSONObject

/** Only documented typed reads. Rust validates arguments and owns the wire protocol. */
class PullRequestReader(private val domain: DomainCalls = DomainEngine.shared) {
    suspend fun list(transport: T3Transport, state: String, involvement: String, query: String, projectId: String?, cursors: JSONObject?): JSONObject {
        val recipe = domain.domain("pullRequests.request", JSONObject().put("action", "list")
            .put("state", state).put("involvement", involvement).put("query", query).put("cursors", cursors)) as JSONObject
        val args = recipe.getJSONObject("payload")
        // The page recipe predates project scope; this is a documented typed LIST input.
        if (projectId != null) args.put("projectId", projectId)
        val result = transport.call(recipe.getString("method"), args) as JSONObject
        for (i in 0 until (result.optJSONArray("entries")?.length() ?: 0)) {
            val entry = result.getJSONArray("entries").getJSONObject(i)
            val link = JSONObject(entry.toString()).put("snapshot", JSONObject(entry.toString()))
            entry.put("badge", domain.domain("pullRequests.sidebarBadge", JSONObject().put("thread",
                JSONObject().put("projectId", entry.optString("projectId")).put("pullRequests", JSONArray().put(link)))))
        }
        return result
    }

    suspend fun read(transport: T3Transport, action: String, reference: JSONObject): JSONObject? {
        if (action == "diff") return transport.pullRequestDiff(reference)
        require(action in listOf("detail", "checks", "activity"))
        val recipe = if (reference.optString("host").isNotEmpty()) domain.domain("pullRequests.request", JSONObject().put("action", action).put("reference", reference)) as JSONObject
            else JSONObject().put("method", "pullRequests.$action").put("payload", reference)
        val result = transport.call(recipe.getString("method"), recipe.getJSONObject("payload")) as? JSONObject ?: return if (action == "checks") JSONObject().put("checks", JSONArray()) else null
        when (action) {
            "detail" -> markdown(result)
            "activity" -> {
                result.optJSONArray("comments")?.let { comments -> for (i in 0 until comments.length()) markdown(comments.getJSONObject(i)) }
                result.optJSONArray("reviewThreads")?.let { threads -> for (i in 0 until threads.length()) {
                    val comments = threads.getJSONObject(i).optJSONArray("comments") ?: continue
                    for (j in 0 until comments.length()) markdown(comments.getJSONObject(j))
                } }
            }
        }
        return result
    }
    private suspend fun markdown(item: JSONObject) {
        item.put("blocks", domain.domain("markdown.blocks", JSONObject().put("markdown", item.optString("body"))))
    }
}
