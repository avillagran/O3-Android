package dev.avillagran.ot3android.ui.environments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.bridge.BridgeCatalogController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** No raw command editor: owner-only actions have no controls. Secret invitations never
 * enter saved state, preferences, logs, or diagnostic payloads.
 */
@Composable
fun BridgeAdministration(bridge: BridgeCatalogController, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val identity by bridge.identity.collectAsState()
    var authority by remember { mutableStateOf(context.getSharedPreferences("shared-drafts", android.content.Context.MODE_PRIVATE).getString("authority", "").orEmpty()) }
    val worker = rememberCoroutineScope()
    var peers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var peer by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var relay by remember { mutableStateOf("") }
    var gatewayHost by remember { mutableStateOf("") }
    var gatewayPort by remember { mutableStateOf("") }
    var lifetime by remember { mutableStateOf("600") }
    var allHosts by remember { mutableStateOf(false) }
    var service by remember { mutableStateOf("t3") }
    val invitationServices = remember { mutableStateListOf<String>() }
    var endpoints by remember { mutableStateOf<List<String>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<JSONObject?>(null) }
    var invitation by remember { mutableStateOf<String?>(null) }
    fun request(command: JSONObject, confirmed: Boolean = false) {
        val selected = peer ?: return
        busy = true; failed = false
        worker.launch {
            try {
                val result = bridge.manage(selected, command, confirmed)
                check(!result.has("error"))
                if (command.optString("action") == "invite") invitation = result.getJSONObject("created").get("invitation").toString()
                else if (command.optString("action") == "endpoints") {
                    val rows = result.optJSONArray("endpoints") ?: result.optJSONObject("endpoints")?.optJSONArray("endpoints")
                    endpoints = (0 until (rows?.length() ?: 0)).mapNotNull { index ->
                        when (val row = rows!!.opt(index)) {
                            is String -> row
                            is JSONObject -> row.optString("url").takeIf { it.isNotEmpty() }
                            else -> null
                        }
                    }
                } else {
                    snapshot = result
                    if (command.optString("action") != "read") snapshot = bridge.manage(selected, JSONObject().put("action", "read"))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true; snapshot = null }
            finally { busy = false }
        }
    }
    LaunchedEffect(bridge) {
        try {
            val capabilities = bridge.capabilities().getJSONArray("features")
            check((0 until capabilities.length()).any { capabilities.getString(it) == "mobile-admin-v1" })
            bridge.open(context.noBackupFilesDir.resolve("omabridge").absolutePath)
            val rows = bridge.peers().getJSONArray("peers")
            peers = (0 until rows.length()).map { rows.getJSONObject(it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.bridge_admin_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(authority, { authority = it }, label = { Text(stringResource(R.string.shared_draft_authority)) })
            TextButton(enabled = authority.isBlank() || (authority.trim().length == 64 && authority.trim().all { it in '0'..'9' || it in 'a'..'f' }), onClick = { context.getSharedPreferences("shared-drafts", android.content.Context.MODE_PRIVATE).edit().putString("authority", authority.trim()).apply() }) { Text(stringResource(R.string.shared_draft_select)) }
            Text(stringResource(R.string.bridge_admin_local_note))
            peers.forEach { row ->
                val id = row.optString("id", row.optString("clientId"))
                TextButton(enabled = !busy && id.isNotBlank(), onClick = { peer = id; snapshot = null; invitation = null; invitationServices.clear(); endpoints = emptyList(); request(JSONObject().put("action", "read")) }) {
                    Text(row.optString("name").ifBlank { id })
                }
                TextButton(enabled = !busy && id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }, onClick = {
                    authority = id
                    context.getSharedPreferences("shared-drafts", android.content.Context.MODE_PRIVATE).edit().putString("authority", id).apply()
                }) { Text(stringResource(R.string.shared_draft_select)) }
            }
            if (peer != null) {
                Text(peer!!)
                TextButton(enabled = !busy, onClick = { request(JSONObject().put("action", "read")) }) { Text(stringResource(R.string.bridge_refresh)) }
                if (snapshot != null) {
                    // Deliberately render selected public fields, never serialize the host snapshot.
                    snapshot?.optJSONObject("device")?.optString("name")?.let { Text(it) }
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.bridge_admin_name)) })
                    TextButton(enabled = !busy && name.isNotBlank(), onClick = { confirmation = JSONObject().put("action", "rename").put("name", name) }) { Text(stringResource(R.string.bridge_admin_rename)) }
                    listOf("publish", "unpublish").forEach { action ->
                        TextButton(enabled = !busy, onClick = { confirmation = JSONObject().put("action", action) }) { Text(stringResource(if (action == "publish") R.string.bridge_admin_publish else R.string.bridge_admin_unpublish)) }
                    }
                    val features = snapshot?.optJSONArray("features")
                    val canIssueInvitation = (0 until (features?.length() ?: 0)).any { features!!.optString(it) == "mobile-admin-v1" }
                    if (canIssueInvitation) {
                        val grants = snapshot?.optJSONArray("peers")
                        val actor = (0 until (grants?.length() ?: 0)).map { grants!!.getJSONObject(it) }
                            .firstOrNull { it.optString("id") == identity?.clientId }
                        val services = actor?.optJSONArray("services")
                        for (i in 0 until (services?.length() ?: 0)) {
                            val granted = services!!.getString(i)
                            FilterChip(granted in invitationServices, { if (granted in invitationServices) invitationServices.remove(granted) else invitationServices.add(granted) }, label = { Text(granted) })
                        }
                        OutlinedTextField(lifetime, { lifetime = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.bridge_admin_ttl)) })
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(allHosts, { allHosts = it }); Text(stringResource(R.string.bridge_admin_all_hosts))
                        }
                        TextButton(enabled = !busy && invitationServices.isNotEmpty() && lifetime.toIntOrNull()?.let { it in 1..86400 } == true,
                            onClick = { confirmation = JSONObject().put("action", "invite").put("services", org.json.JSONArray(invitationServices.toList()))
                                .put("ttl", lifetime.toInt()).put("allHosts", allHosts) }) { Text(stringResource(R.string.bridge_admin_invite)) }
                    }
                    OutlinedTextField(relay, { relay = it }, label = { Text(stringResource(R.string.bridge_admin_relay)) })
                    val publicHttps = runCatching { java.net.URI(relay).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
                    TextButton(enabled = !busy && publicHttps, onClick = { confirmation = JSONObject().put("action", "relay-set").put("relay", relay) }) { Text(stringResource(R.string.bridge_admin_relay_set)) }
                    listOf(true, false).forEach { enabled ->
                        TextButton(enabled = !busy && (!enabled || publicHttps), onClick = { confirmation = JSONObject().put("action", "relay-host").put("enabled", enabled).apply { if (enabled) put("url", relay) } }) {
                            Text(stringResource(if (enabled) R.string.bridge_admin_relay_start else R.string.bridge_admin_relay_stop))
                        }
                    }
                    OutlinedTextField(service, { service = it }, label = { Text(stringResource(R.string.bridge_admin_service)) })
                    OutlinedTextField(gatewayHost, { gatewayHost = it }, label = { Text(stringResource(R.string.bridge_admin_gateway)) })
                    OutlinedTextField(gatewayPort, { gatewayPort = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.bridge_admin_port)) })
                    listOf(true, false).forEach { enabled ->
                        TextButton(enabled = !busy && service.isNotBlank() && (gatewayPort.isBlank() || gatewayPort.toIntOrNull()?.let { it in 1..65535 } == true), onClick = {
                            confirmation = JSONObject().put("action", "gateway-set").put("peer", bridge.identity.value?.clientId ?: return@TextButton)
                                .put("service", service.trim()).put("enabled", enabled).apply {
                                    if (gatewayHost.isNotBlank()) put("host", gatewayHost.trim())
                                    gatewayPort.toIntOrNull()?.let { put("port", it) }
                                }
                        }) { Text(stringResource(if (enabled) R.string.bridge_admin_gateway_start else R.string.bridge_admin_gateway_stop)) }
                    }
                    val pending = snapshot?.optJSONArray("pending")
                    for (i in 0 until (pending?.length() ?: 0)) {
                        val row = pending!!.getJSONObject(i)
                        Text(row.optString("name"))
                        if (row.optBoolean("allHosts")) Text(stringResource(R.string.bridge_admin_local_note))
                        else TextButton(enabled = !busy, onClick = { confirmation = JSONObject().put("action", "approve").put("peer", row.getString("id")).put("expires", row.getLong("expires")) }) { Text(stringResource(R.string.pr_approve)) }
                    }
                    val remotePeers = snapshot?.optJSONArray("peers")
                    for (i in 0 until (remotePeers?.length() ?: 0)) {
                        val row = remotePeers!!.getJSONObject(i)
                        Text(row.optString("name"))
                        if (!row.optBoolean("admin")) TextButton(enabled = !busy, onClick = { confirmation = JSONObject().put("action", "revoke").put("peer", row.getString("id")) }) { Text(stringResource(R.string.bridge_admin_revoke)) }
                    }
                    TextButton(enabled = !busy, onClick = { request(JSONObject().put("action", "endpoints")) }) { Text(stringResource(R.string.bridge_admin_endpoints)) }
                }
            }
            endpoints.forEach { Text(it) }
            if (busy) CircularProgressIndicator()
            if (failed) Text(stringResource(R.string.bridge_admin_failed), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ot3_dialog_close)) } })
    confirmation?.let { command -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(stringResource(R.string.bridge_admin_confirm)) }, text = { Column {
            Text(stringResource(when (command.getString("action")) {
                "rename" -> R.string.bridge_admin_rename
                "publish" -> R.string.bridge_admin_publish
                "unpublish" -> R.string.bridge_admin_unpublish
                "invite" -> R.string.bridge_admin_invite
                "relay-set" -> R.string.bridge_admin_relay_set
                "relay-host" -> if (command.optBoolean("enabled")) R.string.bridge_admin_relay_start else R.string.bridge_admin_relay_stop
                "gateway-set" -> if (command.optBoolean("enabled")) R.string.bridge_admin_gateway_start else R.string.bridge_admin_gateway_stop
                "approve" -> R.string.pr_approve
                else -> R.string.bridge_admin_revoke
            }))
            Text(peer.orEmpty())
            listOf("name", "relay", "url", "host", "port", "service", "ttl", "peer").forEach { key -> if (command.has(key)) Text(command.get(key).toString()) }
            if (command.has("services")) Text(command.getJSONArray("services").join(", "))
            if (command.optBoolean("allHosts")) Text(stringResource(R.string.bridge_admin_all_hosts))
        } },
        confirmButton = { TextButton(onClick = { confirmation = null; request(command, true) }) { Text(stringResource(R.string.bridge_admin_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.ot3_dialog_cancel)) } }) }
    invitation?.let { secret ->
        val link = remember(secret) { "omabridge://pair/" + android.util.Base64.encodeToString(secret.toByteArray(Charsets.UTF_8), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) }
        val bitmap = remember(secret) { runCatching {
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(link, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
            android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until 512) for (x in 0 until 512) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }.getOrNull() }
        AlertDialog(onDismissRequest = { invitation = null }, title = { Text(stringResource(R.string.bridge_admin_invite)) }, text = {
            Column {
                bitmap?.let { androidx.compose.foundation.Image(it.asImageBitmap(), stringResource(R.string.bridge_admin_invite), Modifier.fillMaxWidth().aspectRatio(1f)) }
                TextButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, link)
                    context.startActivity(android.content.Intent.createChooser(intent, null))
                    invitation = null
                }) { Text(stringResource(R.string.bridge_admin_share)) }
            }
        }, confirmButton = { TextButton(onClick = { invitation = null }) { Text(stringResource(R.string.ot3_dialog_close)) } })
    }
}
