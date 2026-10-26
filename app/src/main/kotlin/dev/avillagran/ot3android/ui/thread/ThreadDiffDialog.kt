package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.FiraCode
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

private data class DiffRead(val complete: Boolean = false, val data: JSONObject? = null)

@Composable
internal fun ThreadDiffDialog(
    summary: JSONObject,
    read: suspend (JSONObject, Boolean) -> JSONObject?,
    onClose: () -> Unit,
) {
    var showWhitespace by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val whitespaceLabel = stringResource(R.string.ot3_diff_show_whitespace)
    val currentRead by rememberUpdatedState(read)
    val result by produceState(DiffRead(), summary.toString(), showWhitespace, retry) {
        value = DiffRead()
        value = try { DiffRead(true, currentRead(JSONObject(summary.toString()), !showWhitespace)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DiffRead(true) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Ot3Theme.colors.background, contentColor = Ot3Theme.colors.foreground) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ot3_diff), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose, modifier = Modifier.testTag("thread_diff_close")) { Text(stringResource(R.string.ot3_dialog_close)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showWhitespace, onCheckedChange = { showWhitespace = it }, modifier = Modifier.testTag("thread_diff_whitespace").semantics { contentDescription = whitespaceLabel })
                    Text(whitespaceLabel, style = MaterialTheme.typography.bodySmall)
                }
                when {
                    !result.complete -> Text(stringResource(R.string.ot3_loading_diff))
                    result.data == null -> Column {
                        Text(stringResource(R.string.ot3_diff_missing), color = Ot3Theme.colors.failed)
                        TextButton(onClick = { retry++ }, modifier = Modifier.testTag("thread_diff_retry")) { Text(stringResource(R.string.ot3_dialog_retry)) }
                    }
                    else -> DiffLines(result.data!!, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Unified, wrapped patch rows: the native view owns paths, kinds, numbers and limits. */
@Composable
internal fun DiffLines(view: JSONObject, modifier: Modifier = Modifier) {
    val colors = Ot3Theme.colors
    val files = view.optJSONArray("files").objects()
    LazyColumn(modifier.fillMaxWidth().testTag("thread_diff_lines")) {
        if (files.isEmpty()) item("empty") { Text(stringResource(R.string.ot3_diff_empty)) }
        files.forEachIndexed { fileIndex, file ->
            item("header_$fileIndex") {
                Column(Modifier.fillMaxWidth().background(colors.changesCard).padding(8.dp)) {
                    SelectionContainer { Text(file.optString("path"), fontFamily = FiraCode, style = MaterialTheme.typography.bodySmall) }
                    Text("+${file.optInt("additions")} −${file.optInt("deletions")}", style = MaterialTheme.typography.labelSmall)
                    if (file.optBoolean("missing")) Text(stringResource(R.string.ot3_diff_missing), color = colors.dim)
                    if (file.optBoolean("binary")) Text(stringResource(R.string.ot3_diff_binary), color = colors.dim)
                }
            }
            val rows = file.optJSONArray("rows").objects()
            items(rows.size, key = { "line_${fileIndex}_$it" }) { index ->
                val row = rows[index]
                val tint = when (row.optString("kind")) {
                    "addition" -> colors.done
                    "deletion" -> colors.failed
                    "hunk" -> colors.accent
                    "meta" -> colors.dim
                    else -> colors.dim
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 21.dp).padding(vertical = 2.dp)) {
                    Text(listOf("oldLine", "newLine").joinToString(" / ") { if (row.isNull(it)) "" else row.get(it).toString() },
                        modifier = Modifier.width(53.dp), color = colors.dim, fontFamily = FiraCode, fontSize = 11.sp)
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(row.optString("text"), color = tint, fontFamily = FiraCode, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
