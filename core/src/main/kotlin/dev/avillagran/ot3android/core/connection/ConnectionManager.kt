package dev.avillagran.ot3android.core.connection

import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.transport.T3TransportFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * One [ConnectionSupervisor] per saved environment, created on first use and kept for the
 * life of the process. While the app is in the background ([setForeground] false) running
 * supervisors are stopped; they resume, from their held shell, when it comes back.
 */
class ConnectionManager(
    private val repository: EnvironmentRepository,
    private val cursorStore: ShellCursorStore,
    private val transportFactory: T3TransportFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
    private val cache: dev.avillagran.ot3android.core.thread.ThreadCache? = null,
) {
    private val _supervisors = MutableStateFlow<Map<String, ConnectionSupervisor>>(emptyMap())
    val supervisors: StateFlow<Map<String, ConnectionSupervisor>> = _supervisors.asStateFlow()

    private val routeFallbacks = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Supervisors that were running when the app went to the background. */
    private val paused = mutableSetOf<String>()
    private var foreground = true
    private val followLeases = mutableMapOf<String, Int>()

    /** Serializes creating and forgetting supervisors, which both read the repository. */
    private val createLock = Mutex()
    private val cacheWriters = mutableMapOf<String, Job>()

    /** Connection state per local environment id, for supervisors created so far. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val states: Flow<Map<String, ConnectionState>> = _supervisors.flatMapLatest { map ->
        if (map.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(map.values.map { s -> s.state }) { states -> map.keys.zip(states).toMap() }
        }
    }

    /** The supervisor of [environmentId], created (not started) on first use; null if not saved. */
    suspend fun supervisor(environmentId: String): ConnectionSupervisor? {
        _supervisors.value[environmentId]?.let { return it }
        return createLock.withLock {
            _supervisors.value[environmentId] ?: run {
                // Read under the lock, so a forget() running now cannot leave a supervisor behind.
                val environment = repository.find(environmentId) ?: return@withLock null
                val created = create(environment)
                val authority = try { repository.cacheAuthority(environmentId) } catch (_: java.io.IOException) { null }
                try {
                    if (authority != null) {
                        val saved = cache?.readSidebar(environmentId, authority)
                        if (saved != null && repository.cacheAuthority(environmentId) == authority)
                            created.showCachedShell(dev.avillagran.ot3android.core.shell.ShellReducer.parse(saved))
                    }
                } catch (_: java.io.IOException) { /* Disposable cache; connection proceeds. */ }
                created.also { created ->
                    synchronized(this) { _supervisors.update { it + (environmentId to created) } }
                    // Persist each host independently of UI subscriptions, other hosts and
                    // sidebar.view. A failed presentation must not prevent the next cold start.
                    if (cache != null && authority != null) cacheWriters[environmentId] = scope.launch {
                        combine(created.shell, created.connection) { shell, live -> shell to live }
                            .collect { (shell, live) ->
                                if (shell == null || live == null) return@collect
                                try {
                                    if (repository.cacheAuthority(environmentId) != authority) return@collect
                                    val body = org.json.JSONObject().put("snapshotSequence", shell.sequence)
                                        .put("projects", org.json.JSONArray(shell.projects.map { it.raw }))
                                        .put("threads", org.json.JSONArray(shell.threads.take(2000).map { it.raw }))
                                    cache.writeSidebar(environmentId, authority, body)
                                } catch (_: java.io.IOException) { /* Disposable cache. */ }
                            }
                    }
                }
            }
        }
    }

    /**
     * The route each independent environment may use while its own URL is out of reach, by
     * local id. Replaces the previous set: a row left out stops using its fallback.
     */
    fun setRouteFallbacks(routes: Map<String, String>) {
        routeFallbacks.value = routes
    }

    /** Foreground consumers follow replacement transports after pairing/route refresh. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun connectionFlow(environmentId: String): Flow<Connection?> = flow {
        startIfCurrent(supervisor(environmentId))
        emitAll(supervisors.map { it[environmentId] }.distinctUntilChanged().flatMapLatest { current ->
            startIfCurrent(current)
            current?.connection ?: flowOf(null)
        })
    }

    /** Never revive an orphan after forget(), or open a new stream while backgrounded. */
    fun startIfCurrent(supervisor: ConnectionSupervisor?) {
        synchronized(this) {
            if (supervisor == null || _supervisors.value[supervisor.environmentId] !== supervisor) return
            if (foreground || followLeases.getOrDefault(supervisor.environmentId, 0) > 0) supervisor.start() else paused.add(supervisor.environmentId)
        }
    }

    /** A foreground follow service borrows the same supervisor; closing twice is harmless. */
    suspend fun acquireFollowLease(environmentId: String): AutoCloseable? {
        val supervisor = supervisor(environmentId) ?: return null
        synchronized(this) {
            followLeases[environmentId] = followLeases.getOrDefault(environmentId, 0) + 1
            startIfCurrent(supervisor)
        }
        return object : AutoCloseable {
            private var closed = false
            override fun close() = synchronized(this@ConnectionManager) {
                if (!closed) {
                    closed = true
                    val remaining = followLeases.getOrDefault(environmentId, 1) - 1
                    if (remaining > 0) followLeases[environmentId] = remaining else {
                        followLeases.remove(environmentId)
                        if (!foreground) _supervisors.value[environmentId]?.let {
                            if (it.isRunning) { paused.add(environmentId); it.stop() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Stops and forgets [environmentId]'s supervisor, e.g. after its URL or token changed or
     * before it is removed. Returns once its run has ended (transport closed, cursor
     * written), so deleting the environment afterwards leaves nothing behind.
     */
    suspend fun forget(environmentId: String) {
        val removed = createLock.withLock {
            cacheWriters.remove(environmentId)?.cancelAndJoin()
            synchronized(this) {
                paused.remove(environmentId)
                _supervisors.value[environmentId]?.also { _supervisors.update { it - environmentId } }
            }
        }
        removed?.stopAndJoin()
    }

    /** Probes every open stream and skips pending backoffs (resume, network change). */
    fun wakeAll() {
        _supervisors.value.values.forEach { supervisor -> scope.launch { supervisor.wake() } }
    }

    fun setForeground(foreground: Boolean) {
        synchronized(this) {
            this.foreground = foreground
            if (foreground) {
                paused.mapNotNull { _supervisors.value[it] }.forEach { it.start() }
                paused.clear()
            } else {
                _supervisors.value.values.filter { it.isRunning && followLeases.getOrDefault(it.environmentId, 0) == 0 }.forEach { supervisor ->
                    paused.add(supervisor.environmentId)
                    supervisor.stop()
                }
            }
        }
    }

    private fun create(environment: Environment) = ConnectionSupervisor(
        environment = environment,
        token = { repository.token(environment.id) },
        cursorStore = cursorStore,
        transportFactory = transportFactory,
        scope = scope,
        clock = clock,
        random = random,
        onConnected = { descriptor -> repository.markConnected(environment.id, descriptor.environmentId) },
        io = io,
        computation = computation,
        routeFallbacks = routeFallbacks,
    )
}
