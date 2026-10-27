package dev.avillagran.ot3android.ui.newchat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.home.ThreadManagement
import dev.avillagran.ot3android.core.thread.MessageSender
import dev.avillagran.ot3android.core.thread.OutboxRepository
import dev.avillagran.ot3android.core.thread.PendingCommand
import dev.avillagran.ot3android.core.thread.SendResult
import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class NewChatDraftState(
    val loading: Boolean = true, val busy: Boolean = false, val writable: Boolean = false,
    val projectTitle: String = "", val catalog: String? = null, val selection: String? = null,
    val runtimeMode: String = "approval-required", val text: String = "", val title: String = "",
    val error: Boolean = false, val pending: PendingCommand? = null, val createdThreadId: String? = null,
)

/** Explicit project/model/workspace choices; native APIs own validation and project defaults. */
internal class NewChatDraftViewModel(
    private val environmentId: String, private val projectId: String, private val branch: String?,
    private val connections: ConnectionManager, private val sender: MessageSender,
    private val outbox: OutboxRepository, private val saved: SavedStateHandle,
    private val domain: DomainCalls = DomainEngine.shared,
) : ViewModel() {
    private val threadId = saved.get<String>("createdThreadId") ?: "ot3a-${UUID.randomUUID()}".also { saved["createdThreadId"] = it }
    private val local = MutableStateFlow(NewChatDraftState(text = saved["text"] ?: "", title = saved["title"] ?: "",
        selection = saved["selection"], runtimeMode = saved["runtime"] ?: "approval-required", createdThreadId = saved["acceptedThreadId"]))
    val state = combine(local, outbox.observeThread(environmentId, threadId)) { value, pending -> value.copy(pending = pending.firstOrNull()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)
    private var live: T3Transport? = null

    init {
        viewModelScope.launch {
            val supervisor = connections.supervisor(environmentId)
            if (supervisor == null) { local.update { it.copy(loading = false, error = true) }; return@launch }
            connections.startIfCurrent(supervisor)
            combine(supervisor.connection, supervisor.shell.map { shell -> shell?.let { it.project(projectId)?.raw?.toString() ?: "" } }.distinctUntilChanged()) { connection, project ->
                connection to project
            }.collectLatest { (connection, project) ->
                live = connection?.transport
                if (connection == null || project == null) {
                    local.update { it.copy(loading = connection != null, writable = false) }
                    return@collectLatest
                }
                if (project.isEmpty()) {
                    local.update { it.copy(loading = false, writable = false, error = true) }
                } else load(connection.transport)
            }
        }
    }
    private suspend fun load(transport: T3Transport) {
        local.update { it.copy(loading = true, writable = false, error = false) }
        try {
            val project = connections.supervisor(environmentId)?.shell?.value?.project(projectId) ?: error("project_unavailable")
            val config = transport.call("server.getConfig") as JSONObject
            val settings = config.optJSONObject("settings") ?: JSONObject()
            val scoped = domain.domain("scopedSettings.projectCatalogConfig", JSONObject().put("config", config)
                .put("settings", settings).put("project", project.raw)) as JSONObject
            val catalog = domain.domain("models.catalog", JSONObject().put("config", scoped)) as JSONObject
            val access = ThreadManagement.access(catalog, transport.authSession())
            val defaults = domain.domain("nativeSettings.threadDefaults", JSONObject().put("settings", settings).put("projectId", projectId)) as JSONObject
            val defaultSelection = defaults.optJSONObject("defaultModelSelection")
            if (live !== transport) return
            local.update { current -> current.copy(loading = false, writable = access.canWrite,
                projectTitle = project.title ?: projectId, catalog = catalog.toString(),
                selection = current.selection ?: defaultSelection?.toString()) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (live === transport) local.update { it.copy(loading = false, writable = false, error = true) } }
    }
    fun refresh() { live?.let { transport -> viewModelScope.launch { load(transport) } } }
    fun text(value: String) { saved["text"] = value; local.update { it.copy(text = value) } }
    fun title(value: String) { saved["title"] = value; local.update { it.copy(title = value) } }
    fun select(selection: JSONObject) { saved["selection"] = selection.toString(); local.update { it.copy(selection = selection.toString()) } }
    fun runtime(mode: String) { saved["runtime"] = mode; local.update { it.copy(runtimeMode = mode) } }
    fun option(id: String, value: Any) {
        val selection = local.value.selection?.let(::JSONObject) ?: return
        val options = selection.optJSONArray("options") ?: JSONArray()
        val updated = JSONArray()
        for (index in 0 until options.length()) options.getJSONObject(index).takeIf { it.optString("id") != id }?.let(updated::put)
        updated.put(JSONObject().put("id", id).put("value", value))
        select(selection.put("options", updated))
    }
    fun create(fallbackTitle: String) {
        if (local.value.busy) return
        val current = local.value
        val transport = live ?: return
        if (!current.writable) return
        local.update { it.copy(busy = true, error = false) }
        viewModelScope.launch {
            try {
                val permission = ThreadManagement.access(JSONObject(), transport.authSession())
                check(permission.canWrite && live === transport)
                val existing = outbox.observeThread(environmentId, threadId).first().firstOrNull()
                val result = if (existing != null) sender.retry(transport, existing.commandId) else {
                    val selection = current.selection?.let(::JSONObject) ?: error("model_required")
                    val workspace = JSONObject().put("type", "root").apply { branch?.let { put("branch", it) } }
                    sender.launchThread(transport, environmentId, threadId, JSONObject().put("projectId", projectId)
                        .put("title", current.title.ifBlank { fallbackTitle }).put("generateTitle", current.title.isBlank())
                        .put("modelSelection", selection).put("runtimeMode", current.runtimeMode).put("interactionMode", "default")
                        .put("workspaceStrategy", workspace).put("creationSource", "web")
                        .put("initialMessage", JSONObject().put("text", current.text).put("attachments", JSONArray())))
                }
                when (result) {
                    is SendResult.Accepted -> {
                        saved["acceptedThreadId"] = threadId
                        local.update { it.copy(createdThreadId = threadId, busy = false) }
                    }
                    else -> local.update { it.copy(busy = false, error = true) }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { local.update { it.copy(busy = false, error = true) } }
        }
    }
}
