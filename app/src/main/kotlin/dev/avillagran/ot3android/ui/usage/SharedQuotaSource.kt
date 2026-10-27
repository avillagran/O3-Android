package dev.avillagran.ot3android.ui.usage

import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.usage.QuotaReader
import dev.avillagran.ot3android.core.usage.QuotaUpdate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class QuotaState(val limits: JSONObject? = null, val busy: Boolean = true, val offline: Boolean = false, val failed: Boolean = false)

/** One reducer/subscription per visible environment. No retained entries or jobs after last release. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedQuotaSource(
    private val scope: CoroutineScope,
    private val connection: (String) -> Flow<Connection?>,
    private val refresh: suspend (String, Connection) -> Unit,
    private val read: (Connection) -> Flow<QuotaUpdate> = { QuotaReader().stream(it.transport) },
) {
    private class Entry(val scope: CoroutineScope, val state: StateFlow<QuotaState>, var consumers: Int = 0)
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    fun observe(id: String): Flow<QuotaState> = flow {
        val entry = mutex.withLock {
            entries.getOrPut(id) {
                val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
                Entry(child, connection(id).flatMapLatest { current ->
                    if (current == null) flowOf(QuotaState(busy = false, offline = true)) else connected(id, current)
                }.stateIn(child, SharingStarted.Lazily, QuotaState()))
            }.also { it.consumers++ }
        }
        try { emitAll(entry.state) }
        finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (--entry.consumers == 0) {
                        entry.scope.coroutineContext[Job]!!.cancelAndJoin()
                        entries.remove(id)
                    }
                }
            }
        }
    }

    private fun connected(id: String, current: Connection): Flow<QuotaState> = flow {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            emit(QuotaState())
            try {
                coroutineScope {
                    launch {
                        while (isActive) {
                            try { refresh(id, current) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Probe failure still counts in the application gate. */ }
                            delay(300_000)
                        }
                    }
                    read(current).collect { update ->
                        if (update.closed) error("quota_stream_closed")
                        update.state?.let { attempt = 0; emit(QuotaState(it, busy = false)) }
                    }
                    error("quota_stream_ended")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                emit(QuotaState(busy = false, failed = true))
                delay((1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000))
                attempt++
            }
        }
    }
}
