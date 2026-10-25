package dev.avillagran.ot3android.ui.sidebar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.shell.ProjectIdentity
import dev.avillagran.ot3android.core.shell.Sidebar
import dev.avillagran.ot3android.core.shell.SidebarEnvironment
import dev.avillagran.ot3android.core.shell.SidebarRow
import dev.avillagran.ot3android.core.shell.SidebarView
import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One row of the sidebar's lazy list. */
sealed interface SidebarItem {
    val key: String

    data class ActiveHeader(val count: Int) : SidebarItem {
        override val key get() = "@active"
    }

    /** A row with its project chip ([identity] null when the lib has none for the project). */
    data class Thread(val row: SidebarRow, val identity: ProjectIdentity?) : SidebarItem {
        override val key get() = "thread:${row.key}"
    }

    data class SnoozedHeader(val count: Int) : SidebarItem {
        override val key get() = "@snoozed"
    }

    data class SettledHeader(val count: Int, val expanded: Boolean) : SidebarItem {
        override val key get() = "@settled"
    }
}

data class SidebarUiState(
    val label: String = "",
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** Null until the first shell arrives. */
    val items: List<SidebarItem>? = null,
    val refreshing: Boolean = false,
    /** The environment is not in the catalog (removed while this screen was open, or a stale link). */
    val notFound: Boolean = false,
)

/**
 * Flattens the lib's [Sidebar] sections, in the lib's order, into list rows: active (with the
 * working shelf, empty unless grouped), snoozed, then settled only when [settledExpanded].
 */
fun Sidebar.toItems(settledExpanded: Boolean): List<SidebarItem> = buildList {
    val inbox = active + working
    fun rows(section: List<SidebarRow>) = section.forEach { add(SidebarItem.Thread(it, identity(it))) }
    if (inbox.isNotEmpty()) add(SidebarItem.ActiveHeader(inbox.count { it.depth == 0 }))
    rows(inbox)
    if (snoozed.isNotEmpty()) {
        add(SidebarItem.SnoozedHeader(snoozed.count { it.depth == 0 }))
        rows(snoozed)
    }
    if (settled.isNotEmpty()) {
        add(SidebarItem.SettledHeader(settled.count { it.depth == 0 }, settledExpanded))
        if (settledExpanded) rows(settled)
    }
}

/**
 * The live sidebar of one environment; starts its supervisor. The rows come from the lib's
 * `sidebar.view` over the supervisor's shell, through [domain] (off the main thread).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SidebarViewModel(
    private val environmentId: String,
    private val repository: EnvironmentRepository,
    private val connections: ConnectionManager,
    private val domain: DomainCalls = DomainEngine.shared,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val supervisor = MutableStateFlow<ConnectionSupervisor?>(null)
    private val notFound = MutableStateFlow(false)
    private val settledExpanded = MutableStateFlow(false)
    private val refreshing = MutableStateFlow(false)
    private val label = MutableStateFlow("")

    val state: StateFlow<SidebarUiState> = combine(supervisor, notFound) { s, missing -> s to missing }
        .flatMapLatest { (s, missing) ->
            when {
                s != null -> live(s)
                missing -> flowOf(SidebarUiState(notFound = true))
                else -> flowOf(SidebarUiState())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SidebarUiState())

    private fun live(s: ConnectionSupervisor): Flow<SidebarUiState> {
        val sidebar = combine(label, s.shell) { name, shell -> name to shell }.mapLatest { (name, shell) ->
            shell ?: return@mapLatest null
            try {
                SidebarView.build(domain, listOf(SidebarEnvironment(environmentId, name.ifEmpty { null }, shell)), clock(), environmentId)
            } catch (_: T3Exception) {
                // An environment id the lib refuses (`invalid_environments`): nothing to list.
                EMPTY
            }
        }.flowOn(Dispatchers.Default)
        val items = combine(sidebar, settledExpanded) { view, expanded -> view?.toItems(expanded) }
        return combine(label, s.state, items, refreshing) { label, connection, rows, refreshing ->
            SidebarUiState(label, connection, rows, refreshing)
        }
    }

    init {
        viewModelScope.launch {
            label.value = repository.find(environmentId)?.label.orEmpty()
            val found = connections.supervisor(environmentId)
            if (found == null) {
                notFound.value = true
                return@launch
            }
            found.start()
            supervisor.value = found
        }
    }

    fun toggleSettled() {
        settledExpanded.value = !settledExpanded.value
    }

    fun retry() {
        supervisor.value?.retry()
    }

    /** Pull to refresh: a fresh shell GET. */
    fun refresh() {
        val current = supervisor.value ?: return
        viewModelScope.launch {
            refreshing.value = true
            try {
                current.refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A T3Exception is already in the connection state; anything else is a bug the
                // next stream update or reconnect recovers from. Either way refresh just ends.
            } finally {
                refreshing.value = false
            }
        }
    }

    private companion object {
        val EMPTY = SidebarView.parse(JSONObject())
    }
}
