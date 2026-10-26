package dev.avillagran.ot3android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.prefs.ShelfStore
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.home.HeaderStatus
import dev.avillagran.ot3android.core.home.HeaderStatusDisplay
import dev.avillagran.ot3android.core.home.HomeEnvironment
import dev.avillagran.ot3android.core.home.HomeFilter
import dev.avillagran.ot3android.core.home.HomeInput
import dev.avillagran.ot3android.core.home.HomeStateBuilder
import dev.avillagran.ot3android.core.home.HomeUiState
import dev.avillagran.ot3android.core.home.MessageMatch
import dev.avillagran.ot3android.core.home.MessageSearch
import dev.avillagran.ot3android.core.home.MessageSearchState
import dev.avillagran.ot3android.core.home.Shelf
import dev.avillagran.ot3android.core.home.ShelfState
import dev.avillagran.ot3android.core.home.StatusFilter
import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Home's state holder: every saved environment's supervisor (started here) feeds
 * [HomeStateBuilder], which runs the lib's `sidebar.view` on [domain]'s worker; search,
 * filters and paging live here, shelves in [shelfStore]. Scoped to the Chats graph so Home
 * and its sheets share it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: EnvironmentRepository,
    private val connections: ConnectionManager,
    private val shelfStore: ShelfStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val domain: DomainCalls = DomainEngine.shared,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(HomeFilter())
    private val settledLimit = MutableStateFlow(HomeStateBuilder.SETTLED_INITIAL)

    /** The saved environments (catalog order) with their supervisors, started here; null until the catalog loads. */
    private val supervisors: StateFlow<List<Pair<Environment, ConnectionSupervisor>>?> = repository.environments
        .map { saved -> saved.mapNotNull { env -> connections.supervisor(env.id)?.also { connections.startIfCurrent(it) }?.let { env to it } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val environments: Flow<List<HomeEnvironment>> = supervisors.filterNotNull().flatMapLatest { running ->
        if (running.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                running.map { (env, supervisor) ->
                    combine(supervisor.state, supervisor.shell) { state, shell -> HomeEnvironment(env.id, env.label, state, shell) }
                },
            ) { it.toList() }
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** A row of the last view shows an elapsed time or a countdown (the lib's `time.kind`). */
    private val ticking = MutableStateFlow(false)

    /** The last grouped view's `returns`, passed back to the next one. */
    @Volatile private var returns: JSONObject? = null

    /** Once a second while a row shows an elapsed time or a countdown, else every 30 s for ages. */
    private val now: Flow<Long> = ticking
        .flatMapLatest { fast ->
            flow {
                while (true) {
                    emit(clock())
                    delay(if (fast) 1_000 else 30_000)
                }
            }
        }

    private val header = HeaderStatusDisplay.delayed(environments.map { HeaderStatusDisplay.status(it) })

    private val messages = MessageSearch.states(query, ::searchMessages)

    private data class View(val query: String, val filter: HomeFilter, val settledLimit: Int)

    private val view = combine(query, filter, settledLimit, ::View)

    val state: StateFlow<HomeUiState?> = combine(environments, view, shelfStore.shelves, messages, header) { envs, view, shelves, messages, header ->
        Inputs(envs, view, shelves, messages, header)
    }.combine(now) { inputs, now -> inputs to now }
        .mapLatest { (inputs, now) ->
            val input = HomeInput(
                environments = inputs.environments,
                query = inputs.view.query,
                filter = inputs.view.filter,
                shelves = inputs.shelves,
                settledLimit = inputs.view.settledLimit,
                messages = inputs.messages,
                headerStatus = inputs.header,
                now = now,
                returns = returns.takeIf { inputs.shelves.grouped },
            )
            val sidebar = try {
                HomeStateBuilder.view(domain, input)
            } catch (error: CancellationException) {
                throw error
            } catch (_: T3Exception) {
                // The lib refused the input (an environment id it cannot key): keep the last list.
                return@mapLatest state.value
            }
            returns = sidebar.returns
            HomeStateBuilder.build(input, sidebar).also { state ->
                ticking.value = state.rows.values.any { it.time.kind == "elapsed" || it.time.kind == "countdown" }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private data class Inputs(
        val environments: List<HomeEnvironment>,
        val view: View,
        val shelves: ShelfState,
        val messages: MessageSearchState,
        val header: HeaderStatus,
    )

    fun setQuery(text: String) {
        query.value = text
    }

    fun clearSearch() {
        query.value = ""
    }

    fun setStatusFilter(status: StatusFilter) {
        filter.update { it.copy(status = status) }
    }

    /** ot3's Filter by {project}: the same project again clears it; null is All projects. */
    fun setProjectFilter(projectKey: String?) {
        filter.update { it.copy(projectKey = projectKey?.takeIf { key -> key != it.projectKey }) }
    }

    fun setEnvironmentFilter(environmentId: String?) {
        filter.update { it.copy(environmentId = environmentId) }
    }

    fun toggleShelf(shelf: Shelf) {
        viewModelScope.launch { shelfStore.update { it.withExpanded(shelf, !it.expanded(shelf)) } }
    }

    fun toggleGrouping() {
        viewModelScope.launch { shelfStore.update { it.copy(grouped = !it.grouped) } }
    }

    fun showMoreSettled() {
        settledLimit.update(HomeStateBuilder::nextSettledLimit)
    }

    /** Home's overflow Refresh: a fresh shell for every connected environment. */
    fun refresh() {
        supervisors.value.orEmpty().forEach { (_, supervisor) ->
            viewModelScope.launch {
                try {
                    supervisor.refresh()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Not connected or the request failed: the connection state says so already.
                }
            }
        }
    }

    fun retry(environmentId: String) {
        supervisors.value.orEmpty().firstOrNull { it.first.id == environmentId }?.second?.retry()
    }

    /** `orchestration.searchThreads` on every connected environment at once; failures are skipped. */
    private suspend fun searchMessages(query: String): List<MessageMatch> = coroutineScope {
        val calls = supervisors.value.orEmpty().mapNotNull { (env, supervisor) ->
            val connection = supervisor.connection.value ?: return@mapNotNull null
            async<List<MessageMatch>> {
                try {
                    MessageSearch.parse(env.id, connection.transport.call(MessageSearch.FUNCTION, MessageSearch.arguments(query)))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
        MessageSearch.merge(calls.awaitAll())
    }
}
