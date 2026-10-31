package dev.avillagran.ot3android.core.git

import org.json.JSONObject

/** Copy authoritative identifiers into the native scope validator's required arguments.
 * Existing captured values stay intact so native scope validation can detect a changed checkout.
 * This does not canonicalize paths or infer a checkout from a display label.
 */
internal fun gitTarget(target: JSONObject, shell: JSONObject): JSONObject {
    // A returned scope also has projectRoot/worktreePath. They are output-only and
    // must not be fed back as undeclared arguments to the strict domain binding.
    val input = JSONObject()
    listOf("environment", "thread", "projectId", "cwd", "requestId").forEach { key ->
        if (target.has(key)) input.put(key, target.get(key))
    }
    val threads = shell.optJSONArray("threads") ?: return input
    val thread = (0 until threads.length()).mapNotNull(threads::optJSONObject)
        .find { it.optString("id") == input.optString("thread") } ?: return input
    val projectId = thread.optString("projectId").takeIf { it.isNotBlank() && it != "null" } ?: return input
    val projects = shell.optJSONArray("projects") ?: return input
    val project = (0 until projects.length()).mapNotNull(projects::optJSONObject)
        .find { it.optString("id") == projectId } ?: return input
    if (!input.has("projectId")) input.put("projectId", projectId)
    if (!input.has("cwd")) input.put("cwd", if (thread.isNull("worktreePath")) project.opt("workspaceRoot") else thread.opt("worktreePath"))
    return input
}
