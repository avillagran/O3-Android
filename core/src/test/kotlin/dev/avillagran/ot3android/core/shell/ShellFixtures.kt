package dev.avillagran.ot3android.core.shell

import org.json.JSONArray
import org.json.JSONObject

/** Small shell JSON in the server's shape, for reducer, sidebar and supervisor tests. */
object ShellFixtures {

    fun project(id: String, title: String = id, root: String = "/w/$id", identity: String? = null): JSONObject =
        JSONObject().put("id", id).put("title", title).put("workspaceRoot", root)
            .put("repositoryIdentity", identity?.let { JSONObject().put("canonicalKey", it) } ?: JSONObject.NULL)

    fun thread(id: String, projectId: String = "p1", vararg fields: Pair<String, Any?>): JSONObject {
        val json = JSONObject().put("id", id).put("projectId", projectId).put("title", "Thread $id")
            .put("status", "idle").put("updatedAt", "2026-10-24T10:00:00.000Z")
        fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        return json
    }

    fun body(sequence: Long, projects: List<JSONObject>, threads: List<JSONObject>): JSONObject =
        JSONObject().put("schemaVersion", 2).put("snapshotSequence", sequence)
            .put("projects", JSONArray(projects)).put("threads", JSONArray(threads)).put("archivedThreads", JSONArray())

    fun snapshot(sequence: Long, projects: List<JSONObject>, threads: List<JSONObject>): JSONObject =
        JSONObject().put("kind", "snapshot").put("snapshot", body(sequence, projects, threads))

    /** The metadata-only frame T3 sends after the authoritative snapshot. */
    fun identityRefresh(sequence: Long, roots: List<String>, projects: List<JSONObject>): JSONObject =
        snapshot(sequence, projects, emptyList()).put("resolvedRepositoryIdentityRoots", JSONArray(roots))

    fun threadUpdated(sequence: Long, thread: JSONObject, location: String? = "active"): JSONObject =
        JSONObject().put("kind", "thread.updated").put("sequence", sequence).put("thread", thread)
            .apply { if (location != null) put("location", location) }

    fun threadRemoved(sequence: Long, threadId: String): JSONObject =
        JSONObject().put("kind", "thread.removed").put("sequence", sequence).put("threadId", threadId)

    fun projectUpdated(sequence: Long, project: JSONObject): JSONObject =
        JSONObject().put("kind", "project.updated").put("sequence", sequence).put("project", project)

    fun projectRemoved(sequence: Long, projectId: String): JSONObject =
        JSONObject().put("kind", "project.removed").put("sequence", sequence).put("projectId", projectId)
}
