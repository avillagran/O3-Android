package dev.avillagran.ot3android.ui.pullrequests

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.pullrequests.PullRequestReader
import dev.avillagran.ot3android.core.shell.ShellProject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class PullRequestsState(
    val environments: List<Environment> = emptyList(), val environmentId: String? = null,
    val projects: List<ShellProject> = emptyList(), val projectId: String? = null,
    val filter: String = "open", val involvement: String = "all", val query: String = "",
    val busy: Boolean = false, val offline: Boolean = false, val failed: Boolean = false, val scopeUnavailable: Boolean = false,
    val entries: List<JSONObject> = emptyList(), val result: JSONObject? = null,
)

/** One reader per connection generation; switching scope cancels reads and discards cursors. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PullRequestsViewModel(
    environments: Flow<List<Environment>>, private val connection: (String) -> Flow<Connection?>,
    private val projects: (String) -> Flow<List<ShellProject>> = { flowOf(emptyList()) },
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val read: suspend (Connection, String, String, String, String?, JSONObject?) -> JSONObject = { c, s, i, q, p, cursor -> PullRequestReader().list(c.transport, s, i, q, p, cursor) },
) : ViewModel() {
    private val scopeSelection = MutableStateFlow(saved.get<String>("environment") to saved.get<String>("project"))
    private val filter = saved.getStateFlow("state", "open")
    private val involvement = saved.getStateFlow("involvement", "all")
    private val query = saved.getStateFlow("query", "")
    private val debouncedQuery = query.debounce(300).stateIn(viewModelScope, SharingStarted.Eagerly, query.value)
    private val refresh = MutableStateFlow(0)
    private val more = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val filters = combine(filter, involvement, debouncedQuery) { s, i, q -> PullRequestsState(filter = s, involvement = i, query = q) }
    val state = combine(environments, scopeSelection, filters, refresh) { all, selected, base, _ ->
        val id = selected.first
        base.copy(environments = all, environmentId = id ?: all.firstOrNull()?.id, projectId = selected.second, scopeUnavailable = id != null && all.none { it.id == id })
    }
        .flatMapLatest { base ->
            val id = base.environmentId
            if (base.scopeUnavailable) flowOf(base.copy(failed = true))
            else if (id == null) flowOf(base)
            else projects(id).distinctUntilChanged { a, b -> a.map { it.id to it.title } == b.map { it.id to it.title } }.flatMapLatest { options ->
                val scope = base.copy(projects = options)
                connection(id).flatMapLatest { current ->
                    if (current == null) flowOf(scope.copy(offline = true)) else flow {
                        var page = scope
                        suspend fun load(cursor: JSONObject?) {
                            emit(page.copy(busy = true, failed = false))
                            try {
                                val result = read(current, scope.filter, scope.involvement, scope.query, scope.projectId, cursor)
                                val received = result.optJSONArray("entries")
                                val entries = if (cursor == null) mutableListOf() else page.entries.toMutableList()
                                // Append each server page verbatim. No client precedence or domain merge policy.
                                for (i in 0 until (received?.length() ?: 0)) {
                                    val entry = received!!.getJSONObject(i)
                                    entries.add(entry)
                                }
                                page = scope.copy(entries = entries, result = result)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { page = page.copy(busy = false, failed = true) }
                            emit(page)
                        }
                        load(null)
                        more.collect { if (page.result?.optBoolean("truncated") == true) page.result?.optJSONObject("nextCursors")?.let { load(it) } }
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), PullRequestsState())
    fun selectEnvironment(id: String) { saved["project"] = null; saved["environment"] = id; scopeSelection.value = id to null }
    fun selectProject(id: String?) {
        val environment = state.value.environmentId
        saved["environment"] = environment; saved["project"] = id
        scopeSelection.value = environment to id
    }
    fun selectState(value: String) { saved["state"] = value }
    fun selectInvolvement(value: String) { saved["involvement"] = value }
    fun setQuery(value: String) { saved["query"] = value }
    fun refresh() { refresh.update { it + 1 } }
    fun loadMore() { if (!state.value.busy) more.tryEmit(Unit) }
    companion object { fun key(entry: JSONObject) = "${entry.optString("host")}/${entry.optString("projectId")}/${entry.optString("repository")}#${entry.optInt("number")}" }
}

data class PullRequestState(val tab: String = "detail", val busy: Boolean = false, val offline: Boolean = false, val failed: Boolean = false, val detail: JSONObject? = null, val content: JSONObject? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class PullRequestViewModel(
    environmentId: String, reference: JSONObject, connection: (String) -> Flow<Connection?>,
    private val saved: SavedStateHandle = SavedStateHandle(),
    read: suspend (Connection, String, JSONObject) -> JSONObject? = { c, a, r -> PullRequestReader().read(c.transport, a, r) },
) : ViewModel() {
    private val tab = saved.getStateFlow("tab", "detail")
    private val refresh = MutableStateFlow(0)
    val state = connection(environmentId).flatMapLatest { current ->
        // Data cannot cross a connection epoch, even if an old request finishes late.
        if (current == null) tab.map { PullRequestState(tab = it, offline = true) }
        else channelFlow {
            var detail: JSONObject? = null
            combine(tab, refresh) { page, _ -> page }.collectLatest { page ->
                send(PullRequestState(page, busy = true, detail = detail))
                try {
                    if (detail == null || page == "detail") detail = checkNotNull(read(current, "detail", reference))
                    val content = if (page == "detail") detail else checkNotNull(read(current, page, reference))
                    send(PullRequestState(page, detail = detail, content = content))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { send(PullRequestState(page, failed = true, detail = detail)) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), PullRequestState())
    fun selectTab(value: String) { if (value in listOf("detail", "checks", "activity")) saved["tab"] = value }
    fun refresh() { refresh.update { it + 1 } }
}
