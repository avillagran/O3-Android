package dev.avillagran.ot3android.ui.navigation

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.bridge.BridgeCatalogController
import dev.avillagran.ot3android.core.surfaces.DesktopSurfaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** PNG only. No WebView, HTML runtime, URI loader or disk cache. */
@Composable
internal fun DesktopSurfacesPanel(bridge: BridgeCatalogController, credentialLabel: String, token: suspend () -> String?) {
    var peers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var capabilities by remember { mutableStateOf<Set<String>>(emptySet()) }
    var supported by remember { mutableStateOf(false) }
    var peer by remember { mutableStateOf<String?>(null) }
    var surface by remember { mutableStateOf("memory") }
    var content by remember { mutableStateOf<JSONObject?>(null) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var unknown by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf("30") }
    var minTokens by remember { mutableStateOf("100000") }
    val worker = rememberCoroutineScope()
    val selectedPeer = peer
    val client = remember(selectedPeer) { DesktopSurfaces({ method, path, body ->
        bridge.serviceRequest(checkNotNull(selectedPeer), checkNotNull(token()), method, path, body)
    }) }
    DisposableEffect(client) { onDispose { client.discard() } }
    LaunchedEffect(confirmation) {
        while (confirmation != null && client.canConfirm) kotlinx.coroutines.delay(250)
        if (confirmation != null) { confirmation = null; client.discard(); failed = true }
    }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; failed = false
        worker.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true; content = null; capabilities = emptySet(); confirmation = null; preview = null; client.discard() }
            finally { unknown = client.unknown || client.lastUnknown; busy = false }
        }
    }
    LaunchedEffect(bridge) {
        try {
            val features = bridge.capabilities().getJSONArray("features")
            supported = (0 until features.length()).any { features.optString(it) == "service-request-v1" }
            if (supported) {
                val rows = bridge.peers().getJSONArray("peers")
                peers = (0 until rows.length()).map { rows.getJSONObject(it) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { supported = false }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.surfaces_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.surfaces_enable))
        if (!supported) Text(stringResource(R.string.surfaces_sdk))
        if (supported) {
            Text(stringResource(R.string.surfaces_credential, credentialLabel))
            peers.forEach { row ->
                val id = row.optString("id")
                val grants = row.optJSONArray("remote_services")
                val granted = (0 until (grants?.length() ?: 0)).any { grants!!.optString(it) == "desktop-snapshots" }
                Text(row.optString("name") + "\n" + id)
                if (granted && id.matches(Regex("[a-f0-9]{64}"))) TextButton(enabled = !busy, onClick = {
                    peer = id; capabilities = emptySet(); content = null; confirmation = null; preview = null; unknown = false
                }) { Text(stringResource(R.string.surfaces_select)) }
                else Text(stringResource(R.string.surfaces_missing))
            }
            if (peers.isEmpty()) Text(stringResource(R.string.surfaces_missing))
            if (peer != null) {
                Text(peer!!)
                if (capabilities.isEmpty()) {
                    TextButton(enabled = !busy, onClick = { run {
                        val session = client.read("/v1/session")
                        require(session.getInt("protocol") == 1)
                        val advertised = session.getJSONArray("capabilities")
                        capabilities = (0 until advertised.length()).map { advertised.getString(it) }.toSet()
                        content = session
                    } }) { Text(stringResource(R.string.maintenance_refresh)) }
                }
                val names = listOf("memory" to R.string.preferences_memory_obsidian, "vault" to R.string.maintenance_vault,
                    "host-sync" to R.string.maintenance_sync, "compaction" to R.string.surfaces_compaction,
                    "idle-release" to R.string.surfaces_idle, "build-cleanup" to R.string.maintenance_storage,
                    "documents" to R.string.surfaces_documents, "gallery" to R.string.surfaces_gallery)
                names.filter { (name, _) ->
                    (if (name == "documents") "documents.read" else if (name == "gallery") "gallery.read" else "$name.read") in capabilities
                }.forEach { (name, label) ->
                    TextButton(enabled = !busy, onClick = { surface = name; content = null; confirmation = null; preview = null; client.discard()
                        run {
                            content = client.read("/v1/$name")
                            val settings = content?.optJSONObject("report")?.optJSONObject("settings")
                            enabled = settings?.optBoolean(if (name == "compaction") "autoCompactEnabled" else "idleReleaseEnabled") ?: false
                            minutes = settings?.opt(if (name == "compaction") "autoCompactFallbackMinutes" else "idleReleaseMinutes")?.takeIf { it != JSONObject.NULL }?.toString() ?: ""
                            minTokens = settings?.opt("autoCompactMinTokens")?.takeIf { it != JSONObject.NULL }?.toString() ?: ""
                        }
                    }) { Text(stringResource(label)) }
                }
                if (busy) CircularProgressIndicator()
                if (failed && !unknown) Text(stringResource(when {
                    client.consentRequired -> R.string.surfaces_disabled
                    client.notSent -> R.string.surfaces_not_sent
                    else -> R.string.surfaces_refused
                }))
                if (unknown) Text(stringResource(R.string.surfaces_unknown))
                val data = content
                if (data != null) {
                    if (data.has("contents")) {
                        Text(data.optString("title"), style = MaterialTheme.typography.titleMedium)
                        SelectionContainer { Text(data.getString("contents")) }
                    } else SelectionContainer { Text(data.toString(2)) }
                    val items = data.optJSONArray("items")
                    for (i in 0 until (items?.length() ?: 0)) {
                        val item = items!!.getJSONObject(i)
                        val id = item.optString("id")
                        if (runCatching { java.util.UUID.fromString(id) }.isSuccess) TextButton(enabled = !busy, onClick = {
                            run {
                                val result = client.read("/v1/$surface/$id")
                                if (surface == "gallery") {
                                    preview = decodeDesktopPreview(result)
                                    content = null
                                } else { require(!result.getBoolean("executable")); content = result }
                            }
                        }) { Text(item.optString("title")) }
                    }
                    if (!unknown && "maintenance.confirm" in capabilities && data.has("reportId") && surface in listOf("vault", "build-cleanup", "compaction", "idle-release")) {
                        if (surface in listOf("compaction", "idle-release")) {
                            Row { Switch(enabled, { enabled = it }, enabled = !busy); Text(stringResource(R.string.maintenance_enable)) }
                            OutlinedTextField(minutes, { minutes = it }, label = { Text(stringResource(R.string.surfaces_minutes)) }, enabled = !busy)
                            if (surface == "compaction") OutlinedTextField(minTokens, { minTokens = it }, label = { Text(stringResource(R.string.surfaces_tokens)) }, enabled = !busy)
                        }
                        TextButton(enabled = !busy, onClick = { run {
                            val settings = when (surface) {
                                "compaction" -> JSONObject().put("autoCompactEnabled", enabled)
                                    .put("autoCompactFallbackMinutes", minutes.toInt().also { require(it in 1..1440) })
                                    .put("autoCompactMinTokens", minTokens.toInt().also { require(it in 1000..1000000) })
                                "idle-release" -> JSONObject().put("idleReleaseEnabled", enabled)
                                    .put("idleReleaseMinutes", minutes.toInt().also { require(it in 1..1440) })
                                else -> null
                            }
                            confirmation = client.prepare(surface, when (surface) { "vault" -> "rollout"; "build-cleanup" -> "run"; else -> "settings" }, data.getString("reportId"), settings).toString(2)
                        } }) { Text(stringResource(R.string.surfaces_prepare)) }
                    }
                }
                preview?.let { Image(it.asImageBitmap(), stringResource(R.string.surfaces_gallery), Modifier.fillMaxWidth()) }
                confirmation?.let { exact ->
                    Text(stringResource(R.string.surfaces_confirmation))
                    SelectionContainer { Text(exact) }
                    TextButton(enabled = !busy, onClick = { confirmation = null; run { content = client.confirm() } }) { Text(stringResource(R.string.maintenance_confirm)) }
                    TextButton(enabled = !busy, onClick = { confirmation = null; client.discard() }) { Text(stringResource(R.string.action_back)) }
                }
            }
        }
    }
}

/** Decode only bounded PNG bytes, never HTML, resource references or a URL. */
internal fun decodeDesktopPreview(result: JSONObject): android.graphics.Bitmap {
    require(result.getString("mimeType") == "image/png")
    val bytes = Base64.decode(result.getString("base64"), Base64.DEFAULT)
    require(bytes.size <= 512 * 1024)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..1920 && bounds.outHeight in 1..1080 && bounds.outMimeType == "image/png")
    return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
}
