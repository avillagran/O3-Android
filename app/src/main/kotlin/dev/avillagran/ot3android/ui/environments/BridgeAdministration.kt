package dev.avillagran.ot3android.ui.environments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    var authority by remember { mutableStateOf(context.getSharedPreferences("shared-drafts", android.content.Context.MODE_PRIVATE).getString("authority", "").orEmpty()) }
    val worker = rememberCoroutineScope()
    var peers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var peer by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
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
                else snapshot = result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true; snapshot = null }
            finally { busy = false }
        }
    }
    LaunchedEffect(bridge) {
        try {
            val capabilities = bridge.capabilities().getJSONArray("features")
            check((0 until capabilities.length()).any { capabilities.getString(it) == "mobile-admin-v1" })
            val rows = bridge.peers().getJSONArray("peers")
            peers = (0 until rows.length()).map { rows.getJSONObject(it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.bridge_admin_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(authority, { authority = it }, label = { Text(stringResource(R.string.shared_draft_authority)) })
            TextButton(onClick = { context.getSharedPreferences("shared-drafts", android.content.Context.MODE_PRIVATE).edit().putString("authority", authority.trim()).apply() }) { Text(stringResource(R.string.shared_draft_select)) }
            Text(stringResource(R.string.bridge_admin_local_note))
            peers.forEach { row ->
                val id = row.optString("id", row.optString("clientId"))
                TextButton(enabled = !busy && id.isNotBlank(), onClick = { peer = id; snapshot = null; invitation = null; request(JSONObject().put("action", "read")) }) {
                    Text(row.optString("name").ifBlank { id })
                }
            }
            if (peer != null) {
                Text(peer!!)
                TextButton(enabled = !busy, onClick = { request(JSONObject().put("action", "read")) }) { Text(stringResource(R.string.bridge_refresh)) }
                if (snapshot != null) {
                    // Deliberately render selected public fields, never serialize the host snapshot.
                    snapshot?.optJSONObject("identity")?.optString("name")?.let { Text(it) }
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.bridge_admin_name)) })
                    TextButton(enabled = !busy && name.isNotBlank(), onClick = { confirmation = JSONObject().put("action", "rename").put("name", name) }) { Text(stringResource(R.string.bridge_admin_rename)) }
                    listOf("publish", "unpublish").forEach { action ->
                        TextButton(enabled = !busy, onClick = { confirmation = JSONObject().put("action", action) }) { Text(action) }
                    }
                    TextButton(enabled = !busy, onClick = { confirmation = JSONObject().put("action", "invite").put("services", org.json.JSONArray().put("t3")).put("ttl", 600) }) { Text(stringResource(R.string.bridge_admin_invite)) }
                    TextButton(enabled = !busy, onClick = { request(JSONObject().put("action", "endpoints")) }) { Text(stringResource(R.string.bridge_admin_endpoints)) }
                }
            }
            if (busy) CircularProgressIndicator()
            if (failed) Text(stringResource(R.string.bridge_admin_failed), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ot3_dialog_close)) } })
    confirmation?.let { command -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(stringResource(R.string.bridge_admin_confirm)) }, text = { Text(command.getString("action") + "\n" + peer.orEmpty()) },
        confirmButton = { TextButton(onClick = { confirmation = null; request(command, true) }) { Text(stringResource(R.string.bridge_admin_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.ot3_dialog_cancel)) } }) }
    invitation?.let { secret -> AlertDialog(onDismissRequest = { invitation = null },
        title = { Text(stringResource(R.string.bridge_admin_invite)) }, text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(secret) } },
        confirmButton = { TextButton(onClick = { invitation = null }) { Text(stringResource(R.string.ot3_dialog_close)) } }) }
}
