package dev.avillagran.ot3android.ui.sidebar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.shell.ProjectGroup
import dev.avillagran.ot3android.core.shell.Sidebar
import dev.avillagran.ot3android.core.shell.SidebarModel
import dev.avillagran.ot3android.core.shell.SidebarRow
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the sidebar's lazy list. */
sealed interface SidebarItem {
    val key: String

    data class ActiveHeader(val count: Int) : SidebarItem {
        override val key get() = "@active"
    }

    data class ProjectHeader(val projectId: String?, val title: String?) : SidebarItem {
        override val key get() = "project:${projectId.orEmpty()}"
    }

    data class Thread(val row: SidebarRow) : SidebarItem {
        override val key get() = "thread:${row.thread.id}"
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

/** Flattens a [Sidebar] into list rows; settled rows only when [settledExpanded]. */
fun Sidebar.toItems(settledExpanded: Boolean): List<SidebarItem> = buildList {
    if (active.isNotEmpty()) add(SidebarItem.ActiveHeader(active.sumOf { it.rows.size }))
    active.forEach { group: ProjectGroup ->
        add(SidebarItem.ProjectHeader(group.projectId, group.title))
        group.rows.forEach { add(SidebarItem.Thread(it)) }
    }
    if (settled.isNotEmpty()) {
        add(SidebarItem.SettledHeader(settled.size, settledExpanded))
        if (settledExpanded) settled.forEach { add(SidebarItem.Thread(it)) }
    }
}

/** The live sidebar of one environment; starts its supervisor. */
@OptIn(ExperimentalCoroutinesApi::class)
class SidebarViewModel(
    private val environmentId: String,
    private val repository: EnvironmentRepository,
    private val connections: ConnectionManager,
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
        val items = combine(s.shell, settledExpanded) { shell, expanded ->
            shell?.let { SidebarModel.build(it).toItems(expanded) }
        }.flowOn(Dispatchers.Default)
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
}
