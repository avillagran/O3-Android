package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.ot3android.ui.theme.FiraCode
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class DiffRead(val complete: Boolean = false, val data: JSONObject? = null)

@Composable
internal fun ThreadDiffDialog(
    summary: JSONObject,
    read: suspend (JSONObject, Boolean) -> JSONObject?,
    onClose: () -> Unit,
) {
    var showWhitespace by rememberSaveable { mutableStateOf(false) }
    var wordWrap by rememberSaveable(summary.toString()) { mutableStateOf(true) }
    var selectedPath by rememberSaveable(summary.toString()) { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val whitespaceLabel = stringResource(R.string.ot3_diff_show_whitespace)
    val currentRead by rememberUpdatedState(read)
    val result by produceState(DiffRead(), summary.toString(), showWhitespace, retry) {
        value = DiffRead()
        val completed = try { DiffRead(true, currentRead(JSONObject(summary.toString()), !showWhitespace)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DiffRead(true) }
        // JNI completes off-thread; publish the dialog state on the Android UI dispatcher.
        withContext(Dispatchers.Main.immediate) { value = completed }
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
                    Text(whitespaceLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { wordWrap = !wordWrap }, modifier = Modifier.testTag("thread_diff_wrap")) {
                        Text(stringResource(if (wordWrap) R.string.ot3_diff_no_wrap else R.string.ot3_diff_wrap))
                    }
                }
                when {
                    !result.complete -> Text(stringResource(R.string.ot3_loading_diff))
                    result.data == null -> Column {
                        Text(stringResource(R.string.ot3_diff_missing), color = Ot3Theme.colors.failed)
                        TextButton(onClick = { retry++ }, modifier = Modifier.testTag("thread_diff_retry")) { Text(stringResource(R.string.ot3_dialog_retry)) }
                    }
                    else -> DiffFileBrowser(result.data!!, selectedPath, { selectedPath = it }, Modifier.weight(1f), wordWrap)
                }
            }
        }
    }
}

/** Native file order and paths; this selection is local reading state, not a new read. */
@Composable
internal fun DiffFileBrowser(view: JSONObject, selectedPath: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, wordWrap: Boolean = true) {
    val files = view.optJSONArray("files").objects()
    val selectedIndex = files.indexOfFirst { it.optString("path") == selectedPath }
        .takeIf { it >= 0 } ?: files.indexOfFirst { it.optString("oldPath") == selectedPath }.coerceAtLeast(0)
    val selected = files.getOrNull(selectedIndex)
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier) {
        if (selected != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val previousLabel = stringResource(R.string.ot3_diff_previous_file)
                val nextLabel = stringResource(R.string.ot3_diff_next_file)
                Ot3IconButton("chevron-right", previousLabel, { onSelect(files[selectedIndex - 1].getString("path")) },
                    enabled = selectedIndex > 0, iconRotation = 180f, modifier = Modifier.testTag("thread_diff_previous"))
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth().testTag("thread_diff_file_picker")) {
                        Text(selected.getString("path"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FiraCode)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        files.forEachIndexed { index, file ->
                            DropdownMenuItem(text = { Text(file.getString("path"), fontFamily = FiraCode) },
                                onClick = { onSelect(file.getString("path")); menuOpen = false },
                                modifier = Modifier.testTag("thread_diff_file_$index"))
                        }
                    }
                }
                Ot3IconButton("chevron-right", nextLabel, { onSelect(files[selectedIndex + 1].getString("path")) },
                    enabled = selectedIndex < files.lastIndex, modifier = Modifier.testTag("thread_diff_next"))
            }
        }
        // Changing files starts at its header; a whitespace refresh keeps the selected path.
        key(selected?.optString("path")) { DiffLines(view, Modifier.weight(1f), selectedIndex, wordWrap) }
    }
}

/** Unified patch rows: native owns content; font metrics determine unwrapped width. */
@Composable
internal fun DiffLines(view: JSONObject, modifier: Modifier = Modifier, selectedIndex: Int? = null, wordWrap: Boolean = true) {
    val colors = Ot3Theme.colors
    val files = view.optJSONArray("files").objects().filterIndexed { index, _ -> selectedIndex == null || index == selectedIndex }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val codeStyle = LocalTextStyle.current.copy(fontFamily = FiraCode, fontSize = 12.sp)
    val measuredWidth = remember(view.toString(), selectedIndex, wordWrap, density, measurer, codeStyle) {
        if (wordWrap) 0 else files.maxOfOrNull { file ->
            file.optJSONArray("rows").objects().maxOfOrNull { row ->
                measurer.measure(row.optString("text"), style = codeStyle, softWrap = false, maxLines = 1).size.width
            } ?: 0
        } ?: 0
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val viewportWidth = maxWidth
        val contentWidth = maxOf(viewportWidth, with(density) { measuredWidth.toDp() } + 55.dp)
        Box(if (wordWrap) Modifier else Modifier.horizontalScroll(rememberScrollState()).testTag("thread_diff_horizontal")) {
            LazyColumn(Modifier.width(if (wordWrap) viewportWidth else contentWidth).fillMaxHeight().testTag("thread_diff_lines")) {
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
                                Text(row.optString("text"), color = tint, style = codeStyle, softWrap = wordWrap)
                            }
                        }
                    }
                }
            }
        }
    }
}
