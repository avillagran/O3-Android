package dev.avillagran.ot3android.ui.git

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.git.GitOperations
import org.json.JSONArray
import org.json.JSONObject

/** Integration slot: P20 supplies an authoritative snapshot, capability gates and journal state.
 * onConfirm must call GitOperations.confirm and re-read scopes under the global write guard.
 */
@Composable
fun GitControlsSurface(
    snapshot: JSONObject?, connected: Boolean, canWrite: Boolean, canTerminalWrite: Boolean,
    supportsStackedActions: Boolean, record: JSONObject?, loading: Boolean, error: String?,
    onRefresh: () -> Unit, onConfirm: (JSONObject) -> Unit, onReview: ((String) -> Unit)?,
    onOpenNative: (() -> Unit)?, onReconcile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val identity = snapshot?.toString().orEmpty()
    val recordState = record?.optString("state").orEmpty()
    val locked = record != null && recordState !in setOf("complete", "failed", "reviewed")
    val ready = connected && snapshot != null && !loading && error == null && !locked
    var choice by rememberSaveable(identity, locked, connected) { mutableStateOf("") }
    var actionId by rememberSaveable(identity, locked, connected) { mutableStateOf("") }
    var message by rememberSaveable(identity) { mutableStateOf("") }
    var paths by rememberSaveable(identity) { mutableStateOf(emptyList<String>()) }
    var defaultConfirmed by rememberSaveable(identity, choice) { mutableStateOf(false) }
    var filesConfirmed by rememberSaveable(identity, choice) { mutableStateOf(false) }
    var reconciled by rememberSaveable(record?.optString("id")) { mutableStateOf(false) }
    val status = snapshot?.optJSONObject("status")
    val files = status?.optJSONObject("workingTree")?.optJSONArray("files") ?: JSONArray()
    val scripts = snapshot?.optJSONArray("scripts") ?: JSONArray()
    val operations = listOf("commit" to R.string.ot3_git_commit, "push" to R.string.ot3_git_push,
        "pull" to R.string.ot3_git_pull, "commit_push" to R.string.ot3_git_commit_push,
        "create_pr" to R.string.ot3_git_create_pr, "commit_push_pr" to R.string.ot3_git_commit_push_pr)
    val includesCommit = choice in setOf("commit", "commit_push", "commit_push_pr")
    val defaultRequired = status?.optBoolean("isDefaultRef") == true && choice !in setOf("commit", "pull") && !choice.startsWith("script:")
    val dirty = status?.optBoolean("hasWorkingTreeChanges") == true
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ot3_git_title), style = MaterialTheme.typography.titleLarge)
        Text(snapshot?.optJSONObject("scope")?.optString("cwd").orEmpty())
        Text(status?.optString("refName").orEmpty())
        if (status != null) {
            Text("↑ ${status.optInt("aheadCount")}  ↓ ${status.optInt("behindCount")}")
            Text(stringResource(if (dirty) R.string.ot3_git_dirty else R.string.ot3_git_clean))
        }
        if (!connected) Text(stringResource(R.string.ot3_git_offline))
        if (!canWrite) Text(stringResource(R.string.ot3_git_read_only))
        if (loading) CircularProgressIndicator()
        if (error != null) Text(stringResource(R.string.ot3_git_error), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRefresh, enabled = connected && !loading) { Text(stringResource(R.string.ot3_git_refresh)) }
        if (record != null) {
            Text(stringResource(when(recordState) {
                "complete" -> R.string.ot3_git_complete; "failed" -> R.string.ot3_git_failed
                "reviewed" -> R.string.ot3_git_reviewed; else -> R.string.ot3_git_unknown
            }))
            if (onOpenNative != null) TextButton(onClick = onOpenNative) { Text(stringResource(R.string.ot3_git_open_native)) }
            if (locked) {
                GitCheck(stringResource(R.string.ot3_git_verified), reconciled) { reconciled = it }
                TextButton(onClick = { reconciled = false; onReconcile(record.getString("id")) }, enabled = reconciled) { Text(stringResource(R.string.ot3_git_clear_resolved)) }
            }
        }
        for ((operation, label) in operations) {
            val enabled = ready && canWrite && snapshot.optJSONObject("actions")?.optBoolean(operation) == true && (operation == "pull" || supportsStackedActions)
            TextButton(onClick = { choice = operation; actionId = java.util.UUID.randomUUID().toString() }, enabled = enabled) { Text(stringResource(label)) }
        }
        for (i in 0 until scripts.length()) {
            val script = scripts.getJSONObject(i)
            Text(script.getString("name"))
            Text(script.getString("command"), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { choice = "script:" + script.getString("id"); actionId = java.util.UUID.randomUUID().toString() }, enabled = ready && canTerminalWrite) { Text(stringResource(R.string.ot3_git_run)) }
        }
        if (choice.isNotEmpty()) {
            Text(stringResource(R.string.ot3_git_confirmation))
            if (includesCommit) {
                OutlinedTextField(message, { message = it.take(10000) }, label = { Text(stringResource(R.string.ot3_git_message)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.ot3_git_all_files))
            }
            if (defaultRequired) GitCheck(stringResource(R.string.ot3_git_default_branch), defaultConfirmed) { defaultConfirmed = it }
            if (dirty) GitCheck(stringResource(R.string.ot3_git_dirty_confirm), filesConfirmed) { filesConfirmed = it }
            Row {
                TextButton(onClick = { choice = "" }) { Text(stringResource(R.string.ot3_git_cancel)) }
                Button(onClick = {
                    val captured = choice
                    val intent = GitOperations.intent(snapshot!!, if (captured.startsWith("script:")) "script" else captured)
                        .put("actionId", actionId).put("defaultBranchConfirmed", defaultConfirmed)
                    if (captured.startsWith("script:")) intent.put("scriptId", captured.removePrefix("script:"))
                    if (includesCommit) {
                        if (message.isNotBlank()) intent.put("commitMessage", message.trim())
                        if (paths.isNotEmpty()) intent.put("filePaths", JSONArray(paths))
                    }
                    choice = "" // Detach confirmation before invoking a callback; no double tap.
                    onConfirm(intent)
                }, enabled = ready && (!defaultRequired || defaultConfirmed) && (!dirty || filesConfirmed), modifier = Modifier.testTag("git_confirm")) { Text(stringResource(R.string.ot3_git_confirm)) }
            }
        }
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val path = file.getString("path")
            if (includesCommit) GitCheck(path, path in paths) { paths = if (it) paths + path else paths - path }
            else Text(path)
            Text("+${file.optInt("insertions")} −${file.optInt("deletions")}")
            TextButton(onClick = { onReview?.invoke(path) }, enabled = onReview != null && connected) { Text(stringResource(R.string.ot3_git_review)) }
        }
    }
}

@Composable
private fun GitCheck(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row { Checkbox(checked, change, modifier = Modifier.semantics { contentDescription = label }); Text(label, Modifier.weight(1f).padding(top = 12.dp)) }
}

/** P20 retains preview and journal outside the composition, across navigation/restart. */
@Composable
fun CheckpointRollbackSurface(
    preview: JSONObject?, record: JSONObject?, connected: Boolean, canRestoreFiles: Boolean,
    recoveryPrepared: Boolean, onConfirm: (restoreFiles: Boolean, filesAcknowledged: Boolean) -> Unit,
    onRefresh: () -> Unit, onRecover: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
    errorText: String? = null,
) {
    var restoreFiles by rememberSaveable(preview?.toString()) { mutableStateOf(false) }
    var acknowledge by rememberSaveable(preview?.toString(), restoreFiles) { mutableStateOf(false) }
    var submitted by rememberSaveable(preview?.toString()) { mutableStateOf(false) }
    val state = record?.optString("state").orEmpty()
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ot3_git_rewind), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.ot3_git_rewind_explanation))
        if (errorText != null) Text(errorText, color = MaterialTheme.colorScheme.error)
        if (preview?.optJSONObject("gate")?.optBoolean("allowed") == false && errorText == null) Text(stringResource(R.string.ot3_git_checkpoint_unavailable))
        Text(preview?.optJSONObject("message")?.optString("text").orEmpty())
        val attachments = preview?.optJSONObject("message")?.optJSONArray("attachments")
        if (attachments != null) Text(stringResource(R.string.ot3_git_attachments, attachments.length()))
        if (record == null && !submitted) {
            if (canRestoreFiles) GitCheck(stringResource(R.string.ot3_git_restore_files), restoreFiles) { restoreFiles = it }
            else Text(stringResource(R.string.ot3_git_worktree_required))
            if (restoreFiles) GitCheck(stringResource(R.string.ot3_git_restore_warning), acknowledge) { acknowledge = it }
            if (!recoveryPrepared) Text(stringResource(R.string.ot3_git_recovery_not_ready))
            Button(onClick = { submitted = true; onConfirm(restoreFiles, acknowledge) },
                enabled = connected && recoveryPrepared && preview?.optJSONObject("gate")?.optBoolean("allowed") == true && (!restoreFiles || acknowledge),
                modifier = Modifier.testTag("checkpoint_confirm")) { Text(stringResource(R.string.ot3_git_confirm)) }
        } else {
            Text(stringResource(when (state) {
                "recovery_ready" -> R.string.ot3_git_recovery_ready
                "recovery_failed" -> R.string.ot3_git_recovery_failed
                "recovering" -> R.string.ot3_git_recovering
                "complete" -> R.string.ot3_git_complete
                "failed" -> R.string.ot3_git_failed
                "superseded" -> R.string.ot3_git_superseded
                else -> R.string.ot3_git_unknown
            }))
            if (state in setOf("recovery_ready", "recovery_failed")) Button(onClick = onRecover) { Text(stringResource(R.string.ot3_git_recover)) }
        }
        TextButton(onClick = onRefresh, enabled = connected) { Text(stringResource(R.string.ot3_git_refresh)) }
        TextButton(onClick = onClose) { Text(stringResource(R.string.ot3_git_close)) }
    }
}
