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
    private class Entry(val scope: CoroutineScope, val state: StateFlow<QuotaState>, val refreshes: MutableSharedFlow<Unit>, var consumers: Int = 0)
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    fun observe(id: String): Flow<QuotaState> = flow {
        val entry = mutex.withLock {
            entries.getOrPut(id) {
                val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
                val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
                Entry(child, connection(id).flatMapLatest { current ->
                    if (current == null) flowOf(QuotaState(busy = false, offline = true)) else connected(id, current, refreshes)
                }.stateIn(child, SharingStarted.Lazily, QuotaState()), refreshes)
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

    suspend fun refresh(id: String) {
        mutex.withLock { entries[id]?.refreshes?.tryEmit(Unit) }
    }

    private fun connected(id: String, current: Connection, refreshes: Flow<Unit>): Flow<QuotaState> = channelFlow {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            send(QuotaState())
            try {
                coroutineScope {
                    val status = MutableStateFlow(QuotaState())
                    val publishing = launch { status.collect { send(it) } }
                    launch {
                        merge(refreshes, flow { while (currentCoroutineContext().isActive) { emit(Unit); delay(300_000) } }).collect {
                            try { refresh(id, current) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { status.update { it.copy(busy = false, failed = true) } }
                        }
                    }
                    read(current).collect { update ->
                        if (update.closed) error("quota_stream_closed")
                        update.state?.let { attempt = 0; status.value = QuotaState(it, busy = false) }
                    }
                    publishing.cancel()
                    error("quota_stream_ended")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                send(QuotaState(busy = false, failed = true))
                delay((1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000))
                attempt++
            }
        }
    }
}
