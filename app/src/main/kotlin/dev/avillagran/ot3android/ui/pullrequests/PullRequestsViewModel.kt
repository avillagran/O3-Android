package dev.avillagran.ot3android.ui.pullrequests

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.pullrequests.PullRequestReader
import dev.avillagran.ot3android.core.shell.ShellProject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

data class PullRequestsState(
    val environments: List<Environment> = emptyList(), val environmentId: String? = null,
    val projects: List<ShellProject> = emptyList(), val projectId: String? = null,
    val filter: String = "open", val involvement: String = "all", val query: String = "",
    val busy: Boolean = false, val offline: Boolean = false, val failed: Boolean = false, val scopeUnavailable: Boolean = false,
    val entries: List<JSONObject> = emptyList(), val result: JSONObject? = null,
    val awaitingConnection: Boolean = false,
)

/** One private page set per validated scope/connection; no transport collection while hidden. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PullRequestsViewModel(
    environments: Flow<List<Environment>>, private val connection: (String) -> Flow<Connection?>,
    private val projects: (String) -> Flow<List<ShellProject>> = { flowOf(emptyList()) },
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val read: suspend (Connection, String, String, String, String?, JSONObject?) -> JSONObject = { c, s, i, q, p, cursor -> PullRequestReader().list(c.transport, s, i, q, p, cursor) },
) : ViewModel() {
    private var initialEnvironmentId: String? = null
    private val scopeSelection = MutableStateFlow(saved.get<String>("environment") to saved.get<String>("project"))
    private val filter = saved.getStateFlow("state", "open")
    private val involvement = saved.getStateFlow("involvement", "all")
    private val query = saved.getStateFlow("query", "")
    private val debouncedQuery = query.debounce(300).stateIn(viewModelScope, SharingStarted.Eagerly, query.value)
    private val refresh = MutableStateFlow(0)
    // Presentation state and payloads stay in this navigation-entry ViewModel, never a Bundle.
    val listState = LazyListState()
    private data class Scope(val environment: String, val project: String?, val state: String, val involvement: String, val query: String, val refresh: Int)
    private data class Retained(val scope: Scope, val connection: Connection, val page: PullRequestsState)
    private var retained: Retained? = null
    private val more = Channel<Any>(Channel.CONFLATED)
    private var pagingSession: Any? = null
    private var pagingAvailable = false
    private fun invalidate() {
        retained = null
        pagingAvailable = false
        listState.requestScrollToItem(0)
    }
    private val filters = combine(filter, involvement, query, debouncedQuery) { s, i, draft, settled -> PullRequestsState(filter = s, involvement = i, query = settled, busy = draft != settled) }
    val state = combine(environments, scopeSelection, filters, refresh) { all, selected, base, revision ->
        val id = selected.first ?: initialEnvironmentId ?: all.firstOrNull()?.id?.also {
            initialEnvironmentId = it
            saved["environment"] = it
        }
        base.copy(environments = all, environmentId = id, projectId = selected.second,
            scopeUnavailable = id != null && all.none { it.id == id }) to revision
    }.flatMapLatest { (base, revision) ->
        val id = base.environmentId
        if (base.busy) flowOf(base)
        else if (base.scopeUnavailable || id == null) {
            invalidate()
            flowOf(base.copy(failed = base.scopeUnavailable))
        } else projects(id).distinctUntilChanged { a, b -> a.map { it.id to it.title } == b.map { it.id to it.title } }.flatMapLatest { options ->
            val scope = base.copy(projects = options)
            val identity = Scope(id, scope.projectId, scope.filter, scope.involvement, scope.query, revision)
            connection(id).flatMapLatest { current ->
                if (current == null) {
                    invalidate()
                    flowOf(scope.copy(offline = true))
                } else flow {
                    val cached = retained?.takeIf { it.scope == identity && it.connection.transport === current.transport && it.connection.epoch == current.epoch }
                    if (cached == null) invalidate()
                    var page = cached?.page?.copy(environments = scope.environments, projects = scope.projects) ?: scope
                    val session = Any()
                    pagingSession = session
                    suspend fun load(cursor: JSONObject?) {
                        pagingAvailable = false
                        emit(page.copy(busy = true, failed = false))
                        try {
                            val result = read(current, scope.filter, scope.involvement, scope.query, scope.projectId, cursor)
                            currentCoroutineContext().ensureActive()
                            val received = result.optJSONArray("entries")
                            val entries = if (cursor == null) mutableListOf() else page.entries.toMutableList()
                            // Append server pages verbatim: no client precedence, sort or domain merge.
                            for (i in 0 until (received?.length() ?: 0)) entries.add(received!!.getJSONObject(i))
                            page = scope.copy(entries = entries, result = result)
                            retained = Retained(identity, current, page)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { page = page.copy(busy = false, failed = true) }
                        pagingAvailable = page.result?.optBoolean("truncated") == true && page.result?.optJSONObject("nextCursors") != null
                        emit(page)
                    }
                    try {
                        if (cached == null) load(null)
                        else {
                            pagingAvailable = page.result?.optBoolean("truncated") == true && page.result?.optJSONObject("nextCursors") != null
                            emit(page)
                        }
                        // Buffered session tokens cover an action between emit(page) and receive.
                        // A canceled/offscreen session's queued action cannot page a replacement scope.
                        for (requestedSession in more) if (requestedSession === session && pagingAvailable) {
                            page.result?.optJSONObject("nextCursors")?.let { load(it) }
                        }
                    } finally {
                        if (pagingSession === session) { pagingSession = null; pagingAvailable = false }
                    }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), PullRequestsState(awaitingConnection = true))
    fun selectEnvironment(id: String) { if (id == (scopeSelection.value.first ?: initialEnvironmentId)) return; invalidate(); saved["project"] = null; saved["environment"] = id; scopeSelection.value = id to null }
    fun selectProject(id: String?) {
        if (id == scopeSelection.value.second) return
        val environment = state.value.environmentId ?: scopeSelection.value.first ?: initialEnvironmentId
        invalidate()
        saved["environment"] = environment; saved["project"] = id
        scopeSelection.value = environment to id
    }
    fun selectState(value: String) { if (value == filter.value) return; invalidate(); saved["state"] = value }
    fun selectInvolvement(value: String) { if (value == involvement.value) return; invalidate(); saved["involvement"] = value }
    fun setQuery(value: String) { if (query.value != value) invalidate(); saved["query"] = value }
    fun refresh() { invalidate(); refresh.update { it + 1 } }
    fun loadMore() {
        val session = pagingSession
        if (session != null && pagingAvailable && !state.value.busy) more.trySend(session)
    }
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
