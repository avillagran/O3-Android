package dev.avillagran.ot3android.core.tools

import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/** Collect only while the inspector is visible. Cancellation closes the native subscription. */
class LiveCommandOutput {
    fun observe(transport: T3Transport, reference: JSONObject): Flow<JSONObject> = flow {
        val owner = JSONObject(reference.toString())
        var state: JSONObject? = null
        transport.commandOutput(owner).collect { batch ->
            val result = transport.domain("commandOutput.apply", JSONObject().put("reference", owner)
                .put("state", state).put("frames", batch.items).put("closed", batch.closed)
                .put("error", batch.error)) as JSONObject
            state = result.getJSONObject("state")
            emit(result)
        }
    }
}
