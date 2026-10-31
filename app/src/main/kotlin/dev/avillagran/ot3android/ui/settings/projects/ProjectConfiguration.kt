package dev.avillagran.ot3android.ui.settings.projects

import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/** P20 registers this contribution and supplies the selected host's authenticated transport. */
data class ProjectSettingsRoute(val environmentId: String, val projectId: String) {
    init { require(environmentId.isNotBlank() && projectId.isNotBlank()) }
}

data class ProjectConfigurationState(
    val view: JSONObject? = null,
    val metadata: JSONObject? = null,
    val canMutate: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** An unknown write must be reconciled by an explicit refresh before editing again. */
    val needsRefresh: Boolean = false,
)

/** Scoped native descriptors and validators own defaults, inheritance and write eligibility. */
class ProjectConfiguration(private val route: ProjectSettingsRoute, private val transport: T3Transport, private val writes: dev.avillagran.ot3android.core.transport.ConsumerWrites? = null,
    private val admission: dev.avillagran.ot3android.core.git.CheckoutAdmission? = null) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ProjectConfigurationState())
    val state = mutable.asStateFlow()
    private var config = JSONObject()
    private var settings = JSONObject()
    private var project = JSONObject()

    suspend fun refresh() = exclusive {
        val shell = transport.shell()
        val projects = shell.optJSONObject("snapshot")?.optJSONArray("projects") ?: shell.optJSONArray("projects")
        project = (0 until (projects?.length() ?: 0)).mapNotNull { projects?.optJSONObject(it) }
            .firstOrNull { it.optString("id") == route.projectId } ?: error("project_not_found")
        config = transport.call("server.getConfig") as JSONObject
        settings = transport.call("server.getSettings") as JSONObject
        val session = transport.authSession()
        val permissions = session.optJSONArray("permissions") ?: session.optJSONArray("scopes")
        val write = session.opt("authenticated") == true && (0 until (permissions?.length() ?: 0))
            .any { permissions?.optString(it) == "settings:write" }
        val view = transport.domain("scopedSettings.targetView", JSONObject()
            .put("target", JSONObject().put("environment", route.environmentId).put("projectId", route.projectId))
            .put("config", config).put("settings", settings).put("project", project).put("writeAllowed", write)) as JSONObject
        mutable.value = ProjectConfigurationState(view = view, busy = true,
            metadata = JSONObject().put("title", project.optString("title")).put("autoPull", project.optBoolean("autoPull"))
                .put("defaultThreadEnvMode", project.optString("defaultThreadEnvMode")).put("defaultModelSelection", project.optJSONObject("defaultModelSelection")),
            canMutate = writes != null && writes.pending(route.environmentId) == null && session.optBoolean("authenticated") &&
                (0 until (permissions?.length() ?: 0)).any { permissions!!.optString(it) == "orchestration:operate" })
    }

    /** Only keys returned as writable by the native project view can be submitted. */
    suspend fun update(key: String, value: Any?, clear: Boolean = false) = exclusive {
        check(!mutable.value.needsRefresh) { "refresh_required" }
        val view = mutable.value.view ?: error("settings_unavailable")
        val fields = view.optJSONArray("fields") ?: error("settings_unavailable")
        val field = (0 until fields.length()).map { fields.getJSONObject(it) }.firstOrNull { it.optString("key") == key }
        check(view.optBoolean("writeAllowed") && field?.optBoolean("scopeWritable") == true) { "action_unavailable" }
        val patch = transport.domain("scopedSettings.writePatch", JSONObject()
            .put("input", JSONObject().put("key", key).put("action", if (clear) "clear" else "update").put("value", value ?: JSONObject.NULL))
            .put("config", config).put("settings", settings).put("project", project).put("writeAllowed", true)) as JSONObject
        // No optimistic settings: the next explicit refresh reads authoritative host state.
        transport.call("server.updateSettings", patch)
        mutable.value = mutable.value.copy(view = null, needsRefresh = true)
    }

    fun hasPendingMutation() = writes?.pending(route.environmentId) != null
    suspend fun mutate(input: JSONObject) = exclusive {
        check(mutable.value.canMutate && !mutable.value.needsRefresh)
        val command = dev.avillagran.ot3android.core.transport.NativeParity().project(
            JSONObject(input.toString()).put("projectId", route.projectId), "ot3a-" + java.util.UUID.randomUUID())
        val send: suspend () -> Unit = { writes!!.submit(route.environmentId, transport, "projects.mutate", org.json.JSONArray().put(command)) }
        if (admission != null) admission.command(route.environmentId, send) else send()
        mutable.value = mutable.value.copy(needsRefresh = true, canMutate = false)
    }
    suspend fun retryMutation() = exclusive {
        val retry: suspend () -> Unit = { writes?.retry(route.environmentId, transport); Unit }
        if (admission != null) admission.command(route.environmentId, retry) else retry()
        mutable.value = mutable.value.copy(needsRefresh = true)
    }

    private suspend fun exclusive(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        try { block() }
        catch (error: CancellationException) {
            mutable.value = mutable.value.copy(needsRefresh = true)
            throw error
        }
        catch (error: Exception) {
            val code = (error as? T3Exception)?.code ?: "settings_unavailable"
            mutable.value = mutable.value.copy(error = code, needsRefresh = true)
        }
        finally { mutable.value = mutable.value.copy(busy = false); mutex.unlock() }
    }
}
