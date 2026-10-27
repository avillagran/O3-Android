package dev.avillagran.ot3android.core.usage

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/** Only native sanitized state crosses this boundary. Hosts own connection state and retries. */
data class QuotaUpdate(
    val state: JSONObject?,
    val closed: Boolean = false,
    val error: String? = null,
    val errorMessage: String? = null,
)

class QuotaReader(private val domain: DomainCalls = DomainEngine.shared) {
    /** Each collection starts a fresh native reducer and one server-config subscription. */
    fun stream(transport: T3Transport): Flow<QuotaUpdate> = flow {
        var state: JSONObject? = null
        transport.serverConfigStream().collect { batch ->
            var changed = false
            for (index in 0 until batch.items.length()) {
                val result = domain.domain(
                    "quotaSources.apply",
                    JSONObject().put("state", state).put("event", batch.items.get(index)),
                ) as JSONObject
                state = result.getJSONObject("state")
                changed = changed || result.getBoolean("changed")
            }
            if (changed || batch.closed) emit(QuotaUpdate(state, batch.closed, batch.error, batch.errorMessage))
        }
    }
}
