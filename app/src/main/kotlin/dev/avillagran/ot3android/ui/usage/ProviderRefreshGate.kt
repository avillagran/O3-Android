package dev.avillagran.ot3android.ui.usage

import dev.avillagran.ot3android.core.connection.Connection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Application-scoped probe pacing. An attempted probe counts even when it fails. */
class ProviderRefreshGate(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutex = Mutex()
    private val attempts = mutableMapOf<String, Long>()
    suspend fun refresh(environmentId: String, connection: Connection) {
        val permitted = mutex.withLock {
            val time = now()
            attempts.entries.removeAll { time - it.value >= 300_000 }
            if (environmentId in attempts) false else { attempts[environmentId] = time; true }
        }
        if (permitted) connection.transport.call("server.refreshProviders", JSONObject())
    }
}
