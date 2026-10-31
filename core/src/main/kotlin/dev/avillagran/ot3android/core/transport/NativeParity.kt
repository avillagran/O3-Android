package dev.avillagran.ot3android.core.transport

import org.json.JSONArray
import org.json.JSONObject

/** Portable builders. Callers persist command IDs and exact recipes before dispatch;
 * these helpers never send, infer authorization, or retry a mutation.
 */
class NativeParity(private val domain: DomainCalls = DomainEngine.shared) {
    suspend fun pins(orderedIds: JSONArray, keys: JSONObject, movedId: String, commandIds: JSONObject): JSONArray {
        val plan = domain.domain("sidebar.pinPlan", JSONObject().put("orderedIds", orderedIds).put("keys", keys).put("movedId", movedId)) as JSONArray
        return domain.domain("sidebar.pinCommands", JSONObject().put("plan", plan).put("commandIds", commandIds)) as JSONArray
    }
    suspend fun project(input: JSONObject, commandId: String): JSONObject = domain.domain("projects.mutation",
        JSONObject().put("input", input).put("commandId", commandId)) as JSONObject
    suspend fun providers(settings: JSONObject, config: JSONObject, writable: Boolean): JSONObject = domain.domain("providerInstances.projection",
        JSONObject().put("settings", settings).put("config", config).put("writable", writable)) as JSONObject
    suspend fun providerMutation(input: JSONObject, settings: JSONObject): JSONObject = domain.domain("providerInstances.mutation",
        JSONObject().put("input", input).put("settings", settings)) as JSONObject
    suspend fun confirmProvider(action: String, instanceId: String, payload: JSONObject, saved: JSONObject): Boolean = domain.domain("providerInstances.confirmTarget",
        JSONObject().put("action", action).put("instanceId", instanceId).put("payload", payload).put("saved", saved)) as Boolean
    suspend fun reference(href: String, environmentId: String, threadId: String, environments: JSONArray): JSONObject = domain.domain("reader.reference",
        JSONObject().put("href", href).put("environmentId", environmentId).put("threadId", threadId).put("environments", environments)) as JSONObject
}
