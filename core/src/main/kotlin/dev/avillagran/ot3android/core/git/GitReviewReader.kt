package dev.avillagran.ot3android.core.git

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.T3Transport
import org.json.JSONObject
import java.util.UUID

/** Native patch parsing only. Desktop's complete statistics/renderer snapshot projection
 * has no binding; a truncated preview stays visibly incomplete until a file read.
 */
class GitReviewReader(private val domain: DomainCalls = DomainEngine.shared) {
    suspend fun read(transport: T3Transport, target: JSONObject, sourceKind: String, baseRef: String?,
        ignoreWhitespace: Boolean, path: String = "", previousPath: String? = null): JSONObject {
        val capturedTarget = JSONObject(target.toString())
        require(sourceKind in setOf("working-tree", "branch-range"))
        val scope = scope(transport, capturedTarget)
        val args = JSONObject().put("cwd", scope.getString("cwd")).put("ignoreWhitespace", ignoreWhitespace)
        if (sourceKind == "branch-range" && !baseRef.isNullOrEmpty()) args.put("baseRef", baseRef)
        suspend fun fetch(options: JSONObject): JSONObject {
            val result = transport.call("review.getDiffPreview", options) as JSONObject
            check(result.getString("cwd") == scope.getString("cwd")) { "git_scope_changed" }
            val sources = result.getJSONArray("sources")
            return (0 until sources.length()).map { sources.getJSONObject(it) }.firstOrNull { it.optString("kind") == sourceKind }
                ?: error("review_source_unavailable")
        }
        val source = fetch(args)
        var selected = source
        if (path.isNotEmpty()) {
            // Statistics are fed unchanged to the pinned parser as summary. Its native
            // missing-file rows preserve the browser while loading one complete patch.
            val files = source.optJSONArray("files")
            val file = (0 until (files?.length() ?: 0)).map { files!!.getJSONObject(it) }.firstOrNull { it.optString("path") == path }
            val previous = file?.opt("previousPath") ?: previousPath ?: JSONObject.NULL
            selected = fetch(JSONObject(args.toString()).put("file", JSONObject().put("path", path).put("previousPath", previous).put("sourceKind", sourceKind)))
        }
        val view = domain.domain("diffReview.present", JSONObject().put("result", selected).put("summary", source)) as JSONObject
        val after = scope(transport, capturedTarget)
        check(listOf("environment", "thread", "projectId", "cwd").all { after.get(it) == scope.get(it) }) { "git_scope_changed" }
        return view.put("truncated", source.optBoolean("truncated") || selected.optBoolean("truncated")).put("source", sourceKind)
    }

    suspend fun refs(transport: T3Transport, target: JSONObject, query: String, kind: String, cursor: Long? = null): JSONObject {
        val scope = scope(transport, target)
        val args = JSONObject().put("cwd", scope.getString("cwd")).put("query", query).put("refKind", kind).put("limit", 50)
        if (cursor != null) args.put("cursor", cursor)
        return transport.call("vcs.listRefs", args) as JSONObject
    }

    private suspend fun scope(transport: T3Transport, target: JSONObject): JSONObject {
        val shell = transport.shell()
        return domain.domain("gitControls.scope", gitTarget(target, shell).put("shell", shell)
            .put("requestId", UUID.randomUUID().toString())) as JSONObject
    }
}
