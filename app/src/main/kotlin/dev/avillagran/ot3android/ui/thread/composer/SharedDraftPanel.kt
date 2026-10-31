package dev.avillagran.ot3android.ui.thread.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.composer.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Explicit text-only synchronization. Local URIs/uploads are never exported as grants. */
@Composable
fun SharedDraftPanel(repository: SharedDraftRepository, snapshot: ComposerSnapshot,
    onAdopt: (ComposerSnapshot, String) -> Unit, onClose: () -> Unit) {
    val worker = rememberCoroutineScope()
    val opening = remember { snapshot }
    var originalCursor by remember { mutableStateOf<String?>(null) }
    var originalRevision by remember { mutableStateOf<Any?>(null) }
    var state by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun matches(a: JSONObject, b: JSONObject) = listOf("environment", "project", "kind", "id").all { a.optString(it) == b.optString(it) }
    val scope = remember { SharedDraftRepository.scope(opening.scope) }
    fun run(command: JSONObject? = null) {
        busy = true; failed = false
        worker.launch {
            try {
                if (command != null) repository.stage(command, if (command.optString("op") == "put") originalCursor else null)
                state = repository.cached()
                repository.synchronize()
                state = repository.synchronize()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = repository.cached(); failed = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(repository) {
        val cached = repository.cached()
        state = cached
        originalCursor = cached.optString("cursor").takeIf { it.isNotEmpty() }
        val rows = cached.getJSONArray("documents")
        originalRevision = (0 until rows.length()).map { rows.getJSONObject(it) }
            .firstOrNull { matches(it.getJSONObject("scope"), scope) }?.opt("revision")
    }
    val docs = state?.optJSONArray("documents") ?: JSONArray()
    val thread = (0 until docs.length()).map { docs.getJSONObject(it) }.firstOrNull { matches(it.getJSONObject("scope"), scope) }
    val conflicts = state?.optJSONArray("conflicts") ?: JSONArray()
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.shared_draft_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(repository.authority)
            Text(stringResource(R.string.shared_draft_note))
            if (failed) Text(stringResource(R.string.shared_draft_failed), color = MaterialTheme.colorScheme.error)
            TextButton(enabled = !busy, onClick = { run() }) { Text(stringResource(R.string.bridge_refresh)) }
            val pending = state?.optJSONArray("pending")?.length() ?: 0
            if (pending > 0) Text(stringResource(R.string.shared_draft_pending, pending))
            TextButton(enabled = !busy && state?.has("cursor") == true && opening.attachments.isEmpty() && pending == 0 && conflicts.length() == 0,
                onClick = { run(JSONObject().put("op", "put").put("scope", scope)
                    .put("expected", originalRevision ?: JSONObject.NULL)
                    .put("payload", JSONObject().put("schema", 1).put("text", opening.text).put("attachments", JSONArray())
                        .put("selection", JSONArray().put(opening.selection.start).put(opening.selection.end)))) }) { Text(stringResource(R.string.shared_draft_save)) }
            if (thread != null && !thread.optBoolean("deleted")) {
                Text(thread.getJSONObject("payload").optString("text").take(512))
                TextButton(enabled = !busy, onClick = { onAdopt(opening, thread.getJSONObject("payload").getString("text")); onClose() }) { Text(stringResource(R.string.shared_draft_adopt)) }
                TextButton(enabled = !busy && pending == 0 && conflicts.length() == 0, onClick = {
                    run(JSONObject().put("op", "transfer").put("scope", scope).put("expected", thread.getString("revision"))
                        .put("target", SharedDraftRepository.scope(opening.scope, "stash", UUID.randomUUID().toString())).put("targetExpected", JSONObject.NULL))
                }) { Text(stringResource(R.string.composer_stash)) }
            }
            for (i in 0 until docs.length()) {
                val doc = docs.getJSONObject(i)
                val stash = doc.getJSONObject("scope")
                if (stash.optString("kind") == "stash" && stash.optString("project") == opening.scope.project &&
                    stash.optString("environment") == opening.scope.environment && !doc.optBoolean("deleted")) {
                    Text(doc.getJSONObject("payload").optString("text").take(160))
                    TextButton(enabled = !busy && pending == 0 && conflicts.length() == 0, onClick = {
                        val command = JSONObject().put("op", "transfer").put("scope", stash).put("expected", doc.getString("revision"))
                            .put("target", scope).put("targetExpected", thread?.opt("revision") ?: JSONObject.NULL)
                        if (thread != null && !thread.optBoolean("deleted")) command.put("backup", SharedDraftRepository.scope(opening.scope, "stash", UUID.randomUUID().toString()))
                        run(command)
                    }) { Text(stringResource(R.string.shared_draft_restore)) }
                }
            }
            for (i in 0 until conflicts.length()) {
                val conflict = conflicts.getJSONObject(i)
                Text(stringResource(R.string.shared_draft_conflict), color = MaterialTheme.colorScheme.error)
                val original = conflict.getJSONObject("local")
                Text(original.optJSONObject("payload")?.optString("text").orEmpty())
                // Never auto-rebase. Keeping a branch in the editor is safe and leaves the
                // durable conflict untouched; a future explicit resolution may issue new CAS.
                original.optJSONObject("payload")?.let { payload ->
                    TextButton(enabled = !busy, onClick = { onAdopt(opening, payload.optString("text")); onClose() }) { Text(stringResource(R.string.shared_draft_adopt)) }
                }
            }
            if (busy) CircularProgressIndicator()
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ot3_dialog_close)) } })
}
