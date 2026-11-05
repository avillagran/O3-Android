package dev.avillagran.ot3android.core.data.prefs

import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class HostAppearance(val icon: String? = null, val color: String? = null, val showName: Boolean? = null)
data class HostAppearanceSample(val canonicalId: String, val machine: String, val styles: Map<String, HostAppearance>)

/** Lee únicamente identidad visual; no conserva el resto del estado privado de Desktop. */
class HostAppearanceReader {
    suspend fun read(transport: T3Transport, canonicalId: String): HostAppearanceSample {
        val config = transport.call("server.getConfig") as JSONObject
        val machine = listOf(config.optJSONObject("settings")?.optString("environmentIcon"),
            config.optJSONObject("environment")?.optJSONObject("platform")?.optString("machine"))
            .firstOrNull { it in ICONS } ?: "server"
        val styles = try {
            val folder = (transport.call("filesystem.browse", JSONObject().put("partialPath", "~/.local/state/omarchy-t3-code/")) as JSONObject).getString("parentPath")
            check(folder.startsWith('/') && folder.trimEnd('/').endsWith("/.local/state/omarchy-t3-code"))
            val file = transport.call("projects.readFile", JSONObject().put("cwd", folder).put("relativePath", "ui-state.json").put("allowMissing", true)) as JSONObject
            val content = file.optString("contents")
            if (file.optBoolean("truncated") || content.length > 1_048_576 || content.isBlank()) emptyMap()
            else parse(JSONObject(content).optJSONObject("hostStyles"), canonicalId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyMap() }
        return HostAppearanceSample(canonicalId, machine, styles)
    }
    companion object {
        val ICONS = setOf("server", "cloud", "linux", "desktop", "monitor", "laptop", "mac-mini", "mac-studio")
        fun parse(styles: JSONObject?, canonicalId: String): Map<String, HostAppearance> = styles?.keys()?.asSequence()?.take(64)?.mapNotNull { key ->
            val value = styles.optJSONObject(key) ?: return@mapNotNull null
            val icon = value.optString("icon").takeIf { it in ICONS }
            val color = (value.opt("color") as? String)?.takeIf { it.isEmpty() || it.matches(Regex("#[0-9a-fA-F]{6}")) }
            val showName = value.opt("showName") as? Boolean
            (if (key == "local") canonicalId else key) to HostAppearance(icon, color, showName)
        }?.toMap().orEmpty()
    }
}

/** Un host conserva su estilo propio; los pares completan valores que ya comparten en Desktop. */
fun mergedHostAppearances(samples: Collection<HostAppearanceSample>): Map<String, HostAppearance> {
    val shared = mutableMapOf<String, HostAppearance>()
    samples.forEach { sample -> sample.styles.forEach { (key, value) ->
        val prior = shared[key]
        shared[key] = HostAppearance(prior?.icon ?: value.icon, prior?.color ?: value.color,
            prior?.showName ?: value.showName)
    } }
    return samples.associate { sample ->
        val own = sample.styles[sample.canonicalId]
        val peer = shared[sample.canonicalId]
        sample.canonicalId to HostAppearance(own?.icon ?: peer?.icon ?: sample.machine,
            own?.color ?: peer?.color, own?.showName ?: peer?.showName)
    }
}

/** Se detiene con la conexión de la app; no modifica la configuración de Desktop. */
class SharedHostAppearance(private val connections: ConnectionManager, private val preferences: HomePreferences,
    private val scope: CoroutineScope, private val reader: HostAppearanceReader = HostAppearanceReader(),
) {
    private val mutex = Mutex()
    private val samples = mutableMapOf<String, HostAppearanceSample>()
    fun start() = scope.launch(Dispatchers.IO) {
        connections.supervisors.collectLatest { supervisors -> coroutineScope {
            mutex.withLock { samples.keys.retainAll(supervisors.keys) }
            supervisors.forEach { (localId, supervisor) -> launch {
                supervisor.connection.collectLatest { connection ->
                    if (connection != null) while (currentCoroutineContext().isActive) {
                        val connected = supervisor.state.value as? ConnectionState.Connected ?: break
                        try {
                            val sample = reader.read(connection.transport, connected.descriptor.environmentId ?: localId)
                            mutex.withLock {
                                samples[localId] = sample
                                val merged = mergedHostAppearances(samples.values)
                                val saved = preferences.hostStyles.first()
                                samples.forEach { (id, source) ->
                                    val style = merged.getValue(source.canonicalId)
                                    val previous = saved[id]?.split('|').orEmpty()
                                    preferences.setSharedHostStyle(id, style.icon ?: "server", style.color ?: previous.getOrElse(1) { "" },
                                        style.showName ?: (previous.getOrElse(2) { "false" } == "true"))
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Conserva la última identidad cuando el host no responde. */ }
                        delay(60_000)
                    }
                }
            } }
        } }
    }
}
