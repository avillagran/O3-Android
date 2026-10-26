package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.avillagran.ot3android.R
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avillagran.ot3android.core.thread.ThreadMessage
import dev.avillagran.ot3android.ui.kit.Ot3Spinner
import dev.avillagran.ot3android.ui.theme.FiraCode
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull(a::optJSONObject) }.orEmpty()

/** Draw Rust's complete ordered view. No grouping, Markdown parsing or eligibility rules here. */
@Composable
internal fun TimelineReader(view: JSONObject?, fallback: List<ThreadMessage>, onInspect: suspend (JSONObject) -> JSONObject? = { null }) {
    val rows = view?.optJSONArray("timeline").objects().ifEmpty {
        fallback.map { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text)
            .put("createdAt", it.createdAt).put("streaming", it.streaming).put("rev", it.rev) }
    }.asReversed()
    val list = rememberSaveable(saver = readingAnchorSaver(rows)) { LazyListState() }
    val scope = rememberCoroutineScope()
    val away by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 80 } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, reverseLayout = true, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize().testTag(ThreadScreenTags.TIMELINE)) {
            items(rows, key = { it.getString("id") }) { row ->
                // rev invalidates content, never the item's saveable expansion or reading anchor.
                TimelineRow(row, onInspect)
            }
            if (view?.optBoolean("hasMoreHistory") == true) item("history-boundary") {
                Text("Earlier history is available on desktop", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (away) TextButton(onClick = { scope.launch { list.animateScrollToItem(0) } },
            modifier = Modifier.align(Alignment.BottomEnd).background(Ot3Theme.colors.surface).testTag("thread_jump_latest")) {
            Text("Jump to latest ↓")
        }
    }
}

/**
 * Saves the reading anchor by row id, not only by index. A projection that lands between the
 * last layout and `onSaveInstanceState` (rotation while a row streams) moves rows before the
 * list re-measures; the index then names a neighbour after recreation. The id comes from the
 * last layout, so it is the row the reader was looking at.
 */
private fun readingAnchorSaver(rows: List<JSONObject>): Saver<LazyListState, *> = listSaver(
    save = { state ->
        val anchor = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }
        listOf(anchor?.key as? String ?: "", state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
    },
    restore = { (key, index, offset) ->
        val byKey = rows.indexOfFirst { it.optString("id") == key }
        LazyListState(if (byKey >= 0) byKey else index as Int, offset as Int)
    },
)

@Composable
private fun TimelineRow(row: JSONObject, onInspect: suspend (JSONObject) -> JSONObject?) {
    val role = row.optString("role")
    val id = row.getString("id")
    val user = role == "user"
    val colors = Ot3Theme.colors
    val typing = stringResource(R.string.thread_message_typing)
    Row(Modifier.fillMaxWidth().testTag(ThreadScreenTags.message(id)).semantics { if (row.optBoolean("streaming")) stateDescription = typing }, horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(color = if (user) colors.surface else colors.background, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 760.dp).then(if (user) Modifier.fillMaxWidth(.85f) else Modifier.fillMaxWidth())) {
            Column(Modifier.padding(if (user) 12.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val time = row.optLong("createdAt")
                Text(listOf(if (user) row.optString("attribution").takeUnless { it.isBlank() || it == "null" } ?: "You" else if (role == "assistant") "T3" else role,
                    if (time > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time)) else "").filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = colors.dim)
                when (role) {
                    "user" -> SelectionContainer { Text(row.optString("text"), style = MaterialTheme.typography.bodyMedium) }
                    "assistant" -> {
                        if (row.optJSONArray("blocks").objects().isNotEmpty()) MarkdownBlocks(row.optJSONArray("blocks"))
                        else SelectionContainer { Text(row.optString("text")) }
                        row.optJSONArray("attachments").objects().forEach { Text("Attachment · ${it.optString("name", "Preview unavailable")}", color = colors.dim) }
                    }
                    "activity" -> ActivityBlock(row.getJSONObject("activity"), id, onInspect)
                    "changes" -> ChangesBlock(row.getJSONObject("changes"), id)
                    "plan" -> Fold("Plan", id) { MarkdownBlocks(row.optJSONObject("plan")?.optJSONArray("blocks")) }
                    "error" -> Text(row.optJSONObject("error")?.optString("message").orEmpty(), color = MaterialTheme.colorScheme.error)
                    "context" -> Text("Context compacted", color = colors.dim)
                    "subagents" -> row.optJSONArray("subagents").objects().forEach { Text(listOf(it.optString("title"), it.optString("status"), it.optString("summary")).filter { s -> s.isNotBlank() && s != "null" }.joinToString(" · ")) }
                    "secret" -> Text("Secret request · use desktop to respond", color = colors.dim)
                    else -> Text("Unsupported timeline row: $role", color = colors.dim, modifier = Modifier.testTag("thread_unknown_$id"))
                }
                if (row.optBoolean("streaming")) Ot3Spinner(Modifier.testTag(ThreadScreenTags.streaming(id)), size = 12.dp)
                if (role == "user" || role == "assistant") CopyText(row.optString("text"), "message_$id")
            }
        }
    }
}

@Composable
private fun Fold(title: String, id: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("thread_fold_$id")) { Text("${if (expanded) "▾" else "▸"} $title") }
        if (expanded) content()
    }
}

@Composable
private fun ActivityBlock(activity: JSONObject, id: String, onInspect: suspend (JSONObject) -> JSONObject?) {
    val title = listOf(activity.optString("category"), activity.optString("title"), activity.optString("command"), activity.optString("status"))
        .filter { it.isNotBlank() && it != "null" }.joinToString(" · ")
    Fold(title.ifBlank { "Activity" }, id) {
        val entries = activity.optJSONArray("entries").objects().ifEmpty { listOf(activity) }
        entries.forEach { entry ->
            Text(listOf(entry.optString("category"), entry.optString("title"), entry.optString("command"), entry.optString("status"))
                .filter { it.isNotBlank() && it != "null" }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        entries.forEach { entry ->
            entry.optJSONObject("inspector")?.let { reference -> Inspector(reference, onInspect) }
        }
    }
}

@Composable
private fun Inspector(reference: JSONObject, onInspect: suspend (JSONObject) -> JSONObject?) {
    var failed by remember(reference.toString()) { mutableStateOf(false) }
    var complete by remember(reference.toString()) { mutableStateOf(false) }
    val result by produceState<JSONObject?>(null, reference.toString()) {
        try { value = onInspect(reference) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { complete = true }
    }
    val view = result
    when {
        !complete -> Ot3Spinner(size = 12.dp)
        failed -> Text("Details unavailable", color = MaterialTheme.colorScheme.error)
        view == null -> Text("Details unavailable while disconnected", color = Ot3Theme.colors.dim)
        !view.optBoolean("available") -> Text("Item no longer available", color = Ot3Theme.colors.dim)
        else -> view.optJSONArray("sections").objects().forEach { section ->
            Text(section.optString("label"), style = MaterialTheme.typography.labelSmall)
            SelectionContainer { Text(section.optString("text"), fontFamily = FiraCode, style = MaterialTheme.typography.bodySmall) }
            CopyText(section.optString("text"), "inspector_${section.optString("key")}")
            if (section.optBoolean("truncated")) Text("Truncated", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ChangesBlock(changes: JSONObject, id: String) {
    Fold("Changes +${changes.optInt("additions")} −${changes.optInt("deletions")}", id) {
        changes.optJSONArray("files").objects().forEach { file ->
            SelectionContainer { Text("${file.optString("path")} · ${file.optString("kind")} +${file.optInt("additions")} −${file.optInt("deletions")}", fontFamily = FiraCode, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Renderer-neutral blocks and inline spans supplied by lib-t3-rust 0.3.4. */
@Composable
internal fun MarkdownBlocks(blocks: JSONArray?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.objects().forEachIndexed { index, block ->
            when (block.optString("type")) {
                "code" -> Surface(color = Ot3Theme.colors.surface, shape = RoundedCornerShape(6.dp)) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(block.optString("language"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            CopyText(block.optString("code"), "code_$index")
                        }
                        SelectionContainer { Text(block.optString("code"), fontFamily = FiraCode, fontSize = 12.sp,
                            softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).testTag("thread_code_$index")) }
                    }
                }
                "list" -> block.optJSONArray("items").objects().forEachIndexed { i, item ->
                    Row {
                        Text(if (block.optBoolean("ordered")) "${block.optInt("start", 1) + i}. " else "• ")
                        Box(Modifier.weight(1f)) { MarkdownBlocks(item.optJSONArray("blocks")) }
                    }
                }
                "quote" -> Row { Text("│ ", color = Ot3Theme.colors.dim); Box(Modifier.weight(1f)) { MarkdownBlocks(block.optJSONArray("blocks")) } }
                "rule" -> HorizontalDivider()
                "table" -> SelectionContainer { Text(block.optString("markdown"), fontFamily = FiraCode, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState())) }
                "image" -> Text("Image · ${block.optString("alt")}", color = Ot3Theme.colors.dim)
                "math" -> SelectionContainer { Text(block.optString("tex"), fontFamily = FiraCode) }
                else -> SelectionContainer {
                    Text(inlineSpans(block.optJSONArray("spans")), style = if (block.optString("type") == "heading") when (block.optInt("level")) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    } else MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun inlineSpans(spans: JSONArray?): AnnotatedString = buildAnnotatedString {
    spans.objects().forEach { span ->
        val style = SpanStyle(fontWeight = if (span.optBoolean("strong")) FontWeight.Bold else null,
            fontStyle = if (span.optBoolean("emphasis")) FontStyle.Italic else null,
            fontFamily = if (span.optString("type") == "code") FiraCode else null,
            textDecoration = if (span.optBoolean("strikethrough")) TextDecoration.LineThrough else null)
        val href = span.optJSONObject("link")?.optString("href")
        // Only external web destinations are opened by Android; file/thread links need native routing.
        val web = href?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (web != null) pushLink(LinkAnnotation.Url(web))
        withStyle(style) { append(if (span.optString("type") == "break") "\n" else span.optString("text")) }
        if (web != null) pop()
    }
}

@Suppress("DEPRECATION")
@Composable
private fun CopyText(text: String, id: String) {
    val clipboard = LocalClipboardManager.current
    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }, enabled = text.isNotEmpty(), modifier = Modifier.testTag("thread_copy_$id")) {
        Text("Copy", style = MaterialTheme.typography.labelSmall)
    }
}
