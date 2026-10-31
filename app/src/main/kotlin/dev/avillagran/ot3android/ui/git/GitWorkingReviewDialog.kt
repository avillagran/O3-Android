package dev.avillagran.ot3android.ui.git

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.thread.ThreadReviewDialog
import org.json.JSONObject

/** P20 binds read to GitReviewReader for the captured target and rejects late ref pages
 * by target/query/kind/cursor generation. A file read must preserve its rename previousPath.
 */
@Composable
fun GitWorkingReviewDialog(
    target: JSONObject,
    read: suspend (sourceKind: String, baseRef: String?, ignoreWhitespace: Boolean, path: String) -> JSONObject?,
    refs: JSONObject?, onRefsRequested: (query: String, kind: String, cursor: Long?) -> Unit,
    settings: DiffReviewSettings, onSettingsChange: (DiffReviewSettings) -> Unit,
    onClose: () -> Unit,
) {
    val targetKey = target.toString()
    var source by rememberSaveable(targetKey) { mutableStateOf("working-tree") }
    var base by rememberSaveable(targetKey) { mutableStateOf("") }
    var query by rememberSaveable(targetKey) { mutableStateOf("") }
    var refKind by rememberSaveable(targetKey) { mutableStateOf("local") }
    var picker by rememberSaveable(targetKey) { mutableStateOf(false) }
    val summary = JSONObject().put("target", targetKey).put("source", source).put("base", base)
    ThreadReviewDialog(summary, { _, whitespace -> read(source, base.ifEmpty { null }, whitespace, "") }, onClose,
        settings = settings, onSettingsChange = onSettingsChange,
        readFile = { _, whitespace, path -> read(source, base.ifEmpty { null }, whitespace, path) },
        reviewControls = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { source = "working-tree" }) { Text(stringResource(R.string.ot3_git_uncommitted)) }
                    TextButton(onClick = { source = "branch-range" }) { Text(stringResource(R.string.ot3_git_branch_changes)) }
                    if (source == "branch-range") TextButton(onClick = { picker = !picker; if (picker) onRefsRequested(query, refKind, null) }) {
                        Text(base.ifEmpty { stringResource(R.string.ot3_git_automatic) })
                    }
                }
                if (picker && source == "branch-range") {
                    OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.ot3_git_search_refs)) })
                    Row {
                        TextButton(onClick = { refKind = "local"; onRefsRequested(query, refKind, null) }) { Text(stringResource(R.string.ot3_git_local_refs)) }
                        TextButton(onClick = { refKind = "remote"; onRefsRequested(query, refKind, null) }) { Text(stringResource(R.string.ot3_git_remote_refs)) }
                        TextButton(onClick = { onRefsRequested(query, refKind, null) }) { Text(stringResource(R.string.ot3_git_refresh)) }
                        TextButton(onClick = { base = ""; picker = false }) { Text(stringResource(R.string.ot3_git_automatic)) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        val entries = refs?.optJSONArray("refs")
                        for (i in 0 until (entries?.length() ?: 0)) {
                            val name = entries!!.getJSONObject(i).optString("name")
                            TextButton(onClick = { base = name; picker = false }, enabled = name.isNotEmpty()) { Text(name) }
                        }
                        if (refs != null && !refs.isNull("nextCursor")) TextButton(onClick = { onRefsRequested(query, refKind, refs.getLong("nextCursor")) }) { Text(stringResource(R.string.ot3_git_more_refs)) }
                    }
                }
            }
        })
}
