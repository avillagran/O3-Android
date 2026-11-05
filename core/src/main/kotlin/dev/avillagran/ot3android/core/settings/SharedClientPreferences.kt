package dev.avillagran.ot3android.core.settings

import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject

/** Lee sólo la preferencia compartida de envío; no importa el documento completo. */
class SharedClientPreferencesReader {
    suspend fun followUp(transport: T3Transport): String {
        val folder = (transport.call("filesystem.browse", JSONObject().put("partialPath", "~/.t3/userdata/")) as JSONObject).getString("parentPath")
        check(folder.startsWith('/') && folder.trimEnd('/').endsWith("/.t3/userdata"))
        val file = transport.call("projects.readFile", JSONObject().put("cwd", folder)
            .put("relativePath", "client-settings.json").put("allowMissing", true)) as JSONObject
        check(!file.optBoolean("truncated", true))
        val text = file.optString("contents")
        check(text.length <= 1_048_576)
        return parse(text)
    }
    companion object {
        fun parse(text: String): String {
            val document = if (text.isBlank()) JSONObject() else JSONObject(text)
            val fields = if (document.has("settings")) document.getJSONObject("settings") else document
            val behavior = fields.optString("followUpBehavior")
            check(behavior.isEmpty() || behavior in setOf("steer", "queue"))
            return behavior.ifEmpty { "steer" }
        }
    }
}

/** Conserva el último valor por host y lo actualiza al conectar y mientras esté conectado. */
class SharedClientPreferences(private val connections: ConnectionManager, private val preferences: GeneralClientPreferences,
    private val scope: CoroutineScope, private val reader: SharedClientPreferencesReader = SharedClientPreferencesReader()) {
    fun start() = scope.launch(Dispatchers.IO) {
        connections.supervisors.collectLatest { supervisors -> coroutineScope {
            supervisors.forEach { (id, supervisor) -> launch {
                supervisor.connection.collectLatest { connection ->
                    if (connection != null) while (currentCoroutineContext().isActive) {
                        try { preferences.mirrorFollowUp(id, reader.followUp(connection.transport)) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Conserva el último valor si el host no permite leerlo. */ }
                        delay(60_000)
                    }
                }
            } }
        } }
    }
}
