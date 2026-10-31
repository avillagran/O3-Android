package dev.avillagran.ot3android.core.settings

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Resolve only the explicit target. Never fall back to the selected/default host. */
fun interface SettingsConnections { suspend fun resolve(environment: String): T3Transport? }

/** Common transport for P03 and the other server preference packages. JSON policy stays in Rust. */
class ScopedSettings(
    private val connections: SettingsConnections,
    private val domain: DomainCalls = DomainEngine.shared,
) {
    private val mutex = Mutex()

    suspend fun read(scope: JSONObject): JSONObject = execute(scope, null)
    suspend fun write(scope: JSONObject, input: JSONObject): JSONObject = execute(scope, input)

    private suspend fun execute(scope: JSONObject, input: JSONObject?): JSONObject = mutex.withLock {
        // Copy caller-owned JSON before suspending; scope changes cannot redirect an in-flight write.
        val selected = JSONObject(scope.toString())
        val edit = input?.let { JSONObject(it.toString()) }
        val targets = domain.domain("scopedSettings.validatedTargets", JSONObject().put("scope", selected)) as JSONArray
        val views = JSONArray()
        val errors = JSONArray()
        val saved = JSONArray()
        for (i in 0 until targets.length()) {
            val target = targets.getJSONObject(i)
            var dispatched = false
            try {
                val transport = connections.resolve(target.getString("environment")) ?: error("settings_scope_unavailable")
                val session = transport.authSession()
                val permissions = session.optJSONArray("permissions") ?: session.optJSONArray("scopes") ?: JSONArray()
                val allowed = session.optBoolean("authenticated") && (0 until permissions.length()).any { permissions.optString(it) == "settings:write" }
                val config = transport.call("server.getConfig") as JSONObject
                var raw = transport.call("server.getSettings") as JSONObject
                val project = if (target.isNull("projectId")) null else {
                    val projects = transport.shell().getJSONArray("projects")
                    (0 until projects.length()).map { projects.getJSONObject(it) }
                        .find { it.optString("id") == target.getString("projectId") } ?: error("settings_scope_unavailable")
                }
                // Unreadable files are absent, as on Desktop; never fabricate their contents.
                val file = if (project == null) null else try {
                    val response = transport.call("projects.readFile", JSONObject().put("cwd", project.getString("workspaceRoot"))
                        .put("relativePath", "t3.json").put("allowMissing", true)) as JSONObject
                    if (response.optBoolean("truncated", true)) null else domain.domain("scopedSettings.projectFile",
                        JSONObject().put("contents", response.getString("contents"))) as? JSONObject
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (edit != null) {
                    val patch = domain.domain("scopedSettings.writePatch", JSONObject().put("input", edit).put("config", config)
                        .put("settings", raw).put("project", project ?: JSONObject.NULL).put("writeAllowed", allowed)) as JSONObject
                    val before = raw
                    dispatched = true
                    transport.call("server.updateSettings", patch)
                    raw = transport.call("server.getSettings") as JSONObject
                    val dropped = domain.domain("scopedSettings.writeWasDropped", JSONObject().put("next", patch.getJSONObject("patch"))
                        .put("before", before).put("saved", raw)) as Boolean
                    if (dropped) { dispatched = false; error("settings_write_dropped") }
                    saved.put(target)
                    dispatched = false
                }
                views.put(domain.domain("scopedSettings.targetView", JSONObject().put("target", target).put("config", config)
                    .put("settings", raw).put("project", project ?: JSONObject.NULL).put("file", file ?: JSONObject.NULL)
                    .put("writeAllowed", allowed)))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val code = if (e is T3Exception) e.code else e.message?.takeIf { it in SAFE_ERRORS } ?: "settings_unavailable"
                errors.put(JSONObject().put("target", target).put("error", code).put("uncertain", dispatched))
            }
        }
        domain.domain("scopedSettings.page", JSONObject().put("scope", selected).put("views", views).put("errors", errors)
            .put("saved", saved).put("requested", targets.length())) as JSONObject
    }

    companion object {
        private val SAFE_ERRORS = setOf("settings_scope_unavailable", "settings_write_dropped")
    }
}
