package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
import dev.avillagran.ot3android.ui.navigation.LocalSelectedThreadKey
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull(a::optJSONObject) }.orEmpty()

/** Draw Rust's complete ordered view. No grouping, Markdown parsing or eligibility rules here. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun TimelineReader(view: JSONObject?, fallback: List<ThreadMessage>, onInspect: suspend (JSONObject) -> JSONObject? = { null }, onReadDiff: suspend (JSONObject, Boolean) -> JSONObject? = { _, _ -> null },
    initialPosition: dev.avillagran.ot3android.core.data.prefs.ReadingPosition? = null,
    onPosition: (dev.avillagran.ot3android.core.data.prefs.ReadingPosition) -> Unit = {},
    followLatestRequest: Int = 0,
) {
    val threadKey = LocalSelectedThreadKey.current
    // The modal belongs to the reader, not a lazy row which may leave composition
    // when rotation changes the viewport. Scope saved state to the selected thread.
    val imageGeneration = LocalReaderImages.current.generation
    var openedImage by rememberSaveable(threadKey, imageGeneration) { mutableStateOf<String?>(null) }
    openedImage?.let { descriptor -> ReaderImageDialog(JSONObject(descriptor)) { openedImage = null } }
    var openedInspector by rememberSaveable(threadKey) { mutableStateOf<String?>(null) }
    openedInspector?.let { reference -> ToolInspectorDialog(JSONObject(reference), onInspect) { openedInspector = null } }
    var openedDiff by rememberSaveable(threadKey) { mutableStateOf<String?>(null) }
    openedDiff?.let { summary ->
        val review = LocalReaderReview.current
        ThreadReviewDialog(JSONObject(summary), onReadDiff, onClose = { openedDiff = null }, settings = review.settings, onSettingsChange = review.change, enhancedControls = review.settings != null)
    }
    val rows = view?.optJSONArray("timeline").objects().ifEmpty {
        fallback.map { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text)
            .put("createdAt", it.createdAt).put("streaming", it.streaming).put("rev", it.rev) }
    }.asReversed()
    val list = rememberSaveable(saver = readingAnchorSaver(rows)) {
        val byKey = initialPosition?.let { position -> rows.indexOfFirst { it.optString("id") == position.rowId } } ?: -1
        LazyListState(if (byKey >= 0) byKey else initialPosition?.index?.coerceIn(0, (rows.size - 1).coerceAtLeast(0)) ?: 0,
            initialPosition?.offset ?: 0)
    }
    // LazyColumn preserves visible keys on insertion, including when a new latest
    // row arrives. Keep following as user intent, not a value derived after that
    // insertion has already moved the old anchor away from index zero.
    var followingLatest by rememberSaveable(threadKey) {
        mutableStateOf(list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset <= 80)
    }
    var handledFollowRequest by rememberSaveable(threadKey) { mutableIntStateOf(0) }
    LaunchedEffect(list, threadKey) {
        var wasScrolling = false
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (scrolling) wasScrolling = true
            else if (wasScrolling) {
                followingLatest = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset <= 80
                wasScrolling = false
            }
        }
    }
    LaunchedEffect(followLatestRequest) {
        if (followLatestRequest != handledFollowRequest) {
            handledFollowRequest = followLatestRequest
            followingLatest = true
            list.requestScrollToItem(0)
        }
    }
    // Native row revisions cover streamed blocks as well as plain fallback text.
    val contentRevision = rows.map { Triple(it.optString("id"), it.optString("rev"), it.optString("text")) }
    LaunchedEffect(contentRevision, followingLatest) {
        if (followingLatest && !list.isScrollInProgress) list.requestScrollToItem(0)
    }
    val savePosition by androidx.compose.runtime.rememberUpdatedState(onPosition)
    fun position() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }?.let {
        dev.avillagran.ot3android.core.data.prefs.ReadingPosition(it.key as? String ?: "", list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
    }
    androidx.compose.runtime.LaunchedEffect(list, threadKey) {
        androidx.compose.runtime.snapshotFlow { position() }.debounce(250).distinctUntilChanged().collect { it?.let(savePosition) }
    }
    androidx.compose.runtime.DisposableEffect(list, threadKey) {
        onDispose { position()?.let(savePosition) }
    }
    val scope = rememberCoroutineScope()
    val away by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 80 } }
    CompositionLocalProvider(LocalOpenReaderImage provides { openedImage = it.toString() }) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = list, reverseLayout = true, contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize().testTag(ThreadScreenTags.TIMELINE)) {
                items(rows, key = { it.getString("id") }) { row ->
                    // rev invalidates content, never the item's saveable expansion or reading anchor.
                    TimelineRow(row, { openedInspector = it.toString() }) { openedDiff = it.toString() }
                }
                if (view?.optBoolean("hasMoreHistory") == true) item("history-boundary") {
                    Text("Earlier history is available on desktop", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (away) TextButton(onClick = { scope.launch { followingLatest = true; list.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).background(Ot3Theme.colors.surface).testTag("thread_jump_latest")) {
                Text("Jump to latest ↓")
            }
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
private fun TimelineRow(row: JSONObject, onOpenInspector: (JSONObject) -> Unit, onOpenDiff: (JSONObject) -> Unit) {
    val role = row.optString("role")
    val id = row.getString("id")
    if (role == "user") {
        UserMessage(row)
        return
    }
    val colors = Ot3Theme.colors
    val typing = stringResource(R.string.thread_message_typing)
    Row(Modifier.fillMaxWidth().testTag(ThreadScreenTags.message(id)).semantics { if (row.optBoolean("streaming")) stateDescription = typing }, horizontalArrangement = Arrangement.Start) {
        Surface(color = colors.background, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val time = row.optLong("createdAt")
                if (role != "assistant") Text(listOf(role,
                    if (time > 0) when (LocalReaderTimeFormat.current) {
                        "12-hour" -> java.text.SimpleDateFormat("h:mm a", androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
                        "24-hour" -> java.text.SimpleDateFormat("HH:mm", androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
                        else -> DateFormat.getTimeInstance(DateFormat.SHORT)
                    }.format(Date(time)) else "").filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = colors.dim)
                when (role) {
                    "assistant" -> {
                        if (row.optJSONArray("blocks").objects().isNotEmpty()) MarkdownBlocks(row.optJSONArray("blocks"))
                        else SelectionContainer { Text(row.optString("text")) }
                        MessageAttachments(row)
                    }
                    "activity" -> ActivityBlock(row.getJSONObject("activity"), id, onOpenInspector)
                    "changes" -> ChangesBlock(row.getJSONObject("changes"), id, onOpenDiff)
                    "plan" -> Fold("Plan", id) { MarkdownBlocks(row.optJSONObject("plan")?.optJSONArray("blocks")) }
                    "error" -> Text(row.optJSONObject("error")?.optString("message").orEmpty(), color = MaterialTheme.colorScheme.error)
                    "context" -> Text("Context compacted", color = colors.dim)
                    "subagents" -> row.optJSONArray("subagents").objects().forEach { Text(listOf(it.optString("title"), it.optString("status"), it.optString("summary")).filter { s -> s.isNotBlank() && s != "null" }.joinToString(" · ")) }
                    "secret" -> Text("Secret request · use desktop to respond", color = colors.dim)
                    else -> Text("Unsupported timeline row: $role", color = colors.dim, modifier = Modifier.testTag("thread_unknown_$id"))
                }
                if (row.optBoolean("streaming")) Ot3Spinner(Modifier.testTag(ThreadScreenTags.streaming(id)), size = 12.dp)
                if (role == "assistant" && !row.optBoolean("streaming")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CopyText(row.optString("text"), "message_$id")
                        MessageDate(row.optLong("createdAt"))
                    }
                }
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
internal fun ActivityBlock(activity: JSONObject, id: String, onOpenInspector: (JSONObject) -> Unit) {
    val title = listOf(activity.optString("category"), activity.optString("title"), activity.optString("command"), activity.optString("status"))
        .filter { it.isNotBlank() && it != "null" }.joinToString(" · ")
    Fold(title.ifBlank { "Activity" }, id) {
        val entries = activity.optJSONArray("entries").objects().ifEmpty { listOf(activity) }
        entries.forEachIndexed { index, entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOf(entry.optString("category"), entry.optString("title"), entry.optString("command"), entry.optString("status"))
                    .filter { it.isNotBlank() && it != "null" }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                entry.optJSONObject("inspector")?.let { item ->
                    dev.avillagran.ot3android.ui.kit.Ot3Button(stringResource(R.string.ot3_inspector_details),
                        { onOpenInspector(JSONObject(item.toString())) }, style = dev.avillagran.ot3android.ui.kit.Ot3ButtonStyle.SECONDARY,
                        modifier = Modifier.testTag("thread_inspect_${id}_$index"))
                }
            }
        }
    }
}

@Composable
private fun ChangesBlock(changes: JSONObject, id: String, onOpenDiff: (JSONObject) -> Unit) {
    Fold("Changes +${changes.optInt("additions")} −${changes.optInt("deletions")}", id) {
        TextButton(onClick = { onOpenDiff(changes) }, modifier = Modifier.testTag("thread_diff_$id")) { Text(stringResource(R.string.ot3_open_diff)) }
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
                "code" -> dev.avillagran.ot3android.ui.thread.rendering.VisualCodeBlock(block, index, streaming = LocalReaderStreaming.current)
                "list" -> block.optJSONArray("items").objects().forEachIndexed { i, item ->
                    Row {
                        Text(if (block.optBoolean("ordered")) "${block.optInt("start", 1) + i}. " else "• ")
                        Box(Modifier.weight(1f)) { MarkdownBlocks(item.optJSONArray("blocks")) }
                    }
                }
                "quote" -> Row { Text("│ ", color = Ot3Theme.colors.dim); Box(Modifier.weight(1f)) { MarkdownBlocks(block.optJSONArray("blocks")) } }
                "rule" -> HorizontalDivider()
                "table" -> MarkdownTable(block, index)
                "image" -> MarkdownImage(block)
                "math" -> dev.avillagran.ot3android.ui.thread.rendering.VisualMath(block.optString("tex"), enabled = LocalReaderMath.current)
                else -> SelectionContainer {
                    MarkdownInlineContent(block.optJSONArray("spans"), style = if (block.optString("type") == "heading") when (block.optInt("level")) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    } else MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

internal data class ReaderReviewSettings(val settings: dev.avillagran.ot3android.ui.git.DiffReviewSettings? = null, val change: ((dev.avillagran.ot3android.ui.git.DiffReviewSettings) -> Unit)? = null)
internal val LocalReaderReview = staticCompositionLocalOf { ReaderReviewSettings() }
internal val LocalReaderTimeFormat = staticCompositionLocalOf { "locale" }
internal val LocalReaderMath = staticCompositionLocalOf { true }
internal val LocalReaderStreaming = staticCompositionLocalOf { false }

internal fun inlineSpans(spans: JSONArray?, mathEnabled: Boolean = true): AnnotatedString = buildAnnotatedString {
    spans.objects().forEach { span ->
        val style = SpanStyle(fontWeight = if (span.optBoolean("strong")) FontWeight.Bold else null,
            fontStyle = if (span.optBoolean("emphasis")) FontStyle.Italic else null,
            fontFamily = if (span.optString("type") == "code") FiraCode else null,
            textDecoration = if (span.optBoolean("strikethrough")) TextDecoration.LineThrough else null)
        val link = span.optJSONObject("link")
        // Rust owns destination classification. Android requires a normalized scheme
        // for intent matching; file/thread/anchor destinations still need native routing.
        val web = link?.takeIf { it.optString("kind") in setOf("web", "github") }
            ?.getString("href")?.let { android.net.Uri.parse(it).normalizeScheme().toString() }
        if (web != null) pushLink(LinkAnnotation.Url(web))
        withStyle(style) {
            when (span.optString("type")) {
                "math" -> append(if (mathEnabled) dev.avillagran.ot3android.ui.thread.rendering.mathText(span.optString("tex")) else AnnotatedString(span.optString("tex")))
                "break" -> append("\n")
                else -> append(span.optString("text"))
            }
        }
        if (web != null) pop()
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun CopyText(text: String, id: String) {
    val clipboard = LocalClipboardManager.current
    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }, enabled = text.isNotEmpty(), modifier = Modifier.testTag("thread_copy_$id")) {
        Text(stringResource(R.string.ot3_reader_copy), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun MarkdownImage(image: JSONObject) {
    val fallbackName = stringResource(R.string.ot3_image_label)
    val descriptor = remember(image.toString(), fallbackName) { JSONObject().put("id", image.getString("id"))
        .put("type", "image").put("source", image.getString("url")).put("name", image.optString("alt").ifEmpty { fallbackName }) }
    AttachmentImage(descriptor, markdown = true)
}

/** Text runs retain their order around media; phone-width images get their own line. */
@Composable
internal fun MarkdownInlineContent(spans: JSONArray?, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    var run = JSONArray()
    Column(Modifier.fillMaxWidth()) {
        spans.objects().forEach { span ->
            if (span.optString("type") == "image") {
                if (run.length() > 0) Text(inlineSpans(run, LocalReaderMath.current), style = style, modifier = Modifier.fillMaxWidth())
                run = JSONArray()
                MarkdownImage(span)
            } else run.put(span)
        }
        if (run.length() > 0) Text(inlineSpans(run, LocalReaderMath.current), style = style, modifier = Modifier.fillMaxWidth())
    }
}
