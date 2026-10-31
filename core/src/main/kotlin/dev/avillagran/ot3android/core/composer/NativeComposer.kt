package dev.avillagran.ot3android.core.composer

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import org.json.JSONArray
import org.json.JSONObject

/** Native composer projections and UTF-16 transforms; the caller owns IME and undo. */
class NativeComposer(private val domain: DomainCalls = DomainEngine.shared) {
    suspend fun transform(input: JSONObject): JSONObject =
        domain.domain("composer.transform", JSONObject().put("input", input)) as JSONObject
    suspend fun paths(searchResult: JSONObject): JSONArray = domain.domain("composer.paths", JSONObject().put("result", searchResult)) as JSONArray
    suspend fun catalog(provider: JSONObject, cwd: String, kind: String, query: String): JSONArray =
        domain.domain("composer.catalog", JSONObject().put("provider", provider).put("cwd", cwd).put("kind", kind).put("query", query)) as JSONArray
    suspend fun fileLink(path: String): String = domain.domain("composer.fileLink", JSONObject().put("path", path)) as String
    suspend fun attachmentReference(descriptor: JSONObject): String =
        domain.domain("composer.attachmentReference", JSONObject().put("attachment", descriptor)) as String
    suspend fun planView(projection: JSONObject): JSONObject = domain.domain("planWorkflow.view", JSONObject().put("projection", projection)) as JSONObject
    suspend fun followUp(plan: JSONObject, thread: String, draft: String): JSONObject = domain.domain("planWorkflow.followUp",
        JSONObject().put("plan", plan).put("threadId", thread).put("draft", draft)) as JSONObject
    suspend fun attachSourceRef(command: JSONObject, followUp: JSONObject): JSONObject = domain.domain("planWorkflow.attachSourceRef",
        JSONObject().put("command", command).put("followUp", followUp)) as JSONObject
}
