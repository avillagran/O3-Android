package dev.avillagran.ot3android.core.awareness

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Session-local baseline, owned by Rust. Cold process starts seed silently; no shell or credential is stored. */
class ThreadAwareness(private val domain: DomainCalls = DomainEngine.shared) {
    private val mutex = Mutex()
    private var baseline: JSONObject? = null

    suspend fun evaluate(
        environments: JSONArray, now: Long, onScreen: JSONObject? = null,
        preferences: JSONObject? = null, locale: String = "en", resumed: Boolean = false,
    ): JSONObject = mutex.withLock {
        val args = JSONObject().put("environments", environments).put("now", now)
            .put("lifecycle", if (baseline == null) "cold" else if (resumed) "resumed" else "running")
            .put("locale", locale)
        baseline?.let { args.put("baseline", it) }
        onScreen?.let { args.put("onScreen", it) }
        preferences?.let { args.put("preferences", it) }
        val result = domain.domain("awareness.view", args) as JSONObject
        baseline = result.getJSONObject("baseline")
        result
    }
}
