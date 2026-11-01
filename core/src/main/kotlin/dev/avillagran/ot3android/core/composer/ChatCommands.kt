package dev.avillagran.ot3android.core.composer

import dev.avillagran.ot3android.core.thread.MessageSender
import dev.avillagran.ot3android.core.transport.DomainCalls
import org.json.JSONArray
import org.json.JSONObject

/** Platform command assembly only. Rust owns plan policy, launch recipes and wire validation. */
class ChatCommands(private val native: DomainCalls) {
    suspend fun mode(enabled: Boolean, provider: JSONObject?, requested: String): JSONObject =
        native.domain("planWorkflow.mode", JSONObject().put("planModeEnabled", enabled)
            .put("provider", provider).put("requested", requested)) as JSONObject

    suspend fun launch(input: JSONObject): JSONObject {
        val recipe = native.domain("composer.newThread", JSONObject().put("input", input.put("build", true))) as JSONObject
        check(recipe.optBoolean("ready")) { "new_thread_recipe_unavailable" }
        return recipe.getJSONObject("request")
    }

    companion object {
        fun step(command: JSONObject): JSONObject = JSONObject().put("fn", MessageSender.DISPATCH).put("args", command)

        /** Immutable command ids and original text survive process death; only nextStep advances. */
        fun envelope(id: String, text: String, steps: JSONArray, navigate: String? = null): JSONObject =
            JSONObject().put("commandId", id).put("text", text).put(MessageSender.CHAT_STEPS, steps)
                .put("nextStep", 0).apply { navigate?.let { put("navigateThread", it) } }

        fun interaction(id: String, thread: String, mode: String): JSONObject = JSONObject()
            .put("type", "thread.interaction-mode.set").put("commandId", id)
            .put("threadId", thread).put("interactionMode", mode)
    }
}
