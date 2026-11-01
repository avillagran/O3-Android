package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import dev.avillagran.ot3android.ui.thread.reader.*

internal fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull(a::optJSONObject) }.orEmpty()

/** Draw Rust's complete ordered view. No grouping, Markdown parsing or eligibility rules here. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun TimelineReader(view: JSONObject?, fallback: List<ThreadMessage>, onInspect: suspend (JSONObject) -> JSONObject? = { null }, onReadDiff: suspend (JSONObject, Boolean) -> JSONObject? = { _, _ -> null },
    initialPosition: dev.avillagran.ot3android.core.data.prefs.ReadingPosition? = null,
    onPosition: (dev.avillagran.ot3android.core.data.prefs.ReadingPosition) -> Unit = {},
    followLatestRequest: Int = 0,
    actions: TranscriptActions = TranscriptActions(),
    findRequest: Int? = null,
    refreshing: Boolean = false,
    onLoadOlder: () -> Unit = {},
) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun releaseEditor() { focus.clearFocus(force = true); keyboard?.hide() }
    val threadKey = LocalSelectedThreadKey.current
    var finding by rememberSaveable(threadKey) { mutableStateOf(false) }
    LaunchedEffect(findRequest) { if (findRequest != null && findRequest > 0) finding = true }
    var query by rememberSaveable(threadKey) { mutableStateOf("") }
    var selectedMatch by rememberSaveable(threadKey) { mutableStateOf<String?>(null) }
    var matchOffset by rememberSaveable(threadKey) { mutableIntStateOf(0) }
    // The modal belongs to the reader, not a lazy row which may leave composition
    // when rotation changes the viewport. Scope saved state to the selected thread.
    val imageGeneration = LocalReaderImages.current.generation
    var openedImage by rememberSaveable(threadKey, imageGeneration) { mutableStateOf<String?>(null) }
    openedImage?.let { descriptor -> ReaderImageDialog(JSONObject(descriptor)) { releaseEditor(); openedImage = null } }
    var openedInspector by rememberSaveable(threadKey) { mutableStateOf<String?>(null) }
    openedInspector?.let { reference -> ToolInspectorDialog(JSONObject(reference), onInspect, threadKey?.let { dev.avillagran.ot3android.core.tools.ToolInspectorOwner(it.substringBefore('/'), it.substringAfter('/')) }) { releaseEditor(); openedInspector = null } }
    var openedDiff by rememberSaveable(threadKey) { mutableStateOf<String?>(null) }
    openedDiff?.let { summary ->
        val review = LocalReaderReview.current
        ThreadReviewDialog(JSONObject(summary), onReadDiff, onClose = { releaseEditor(); openedDiff = null }, settings = review.settings, onSettingsChange = review.change, enhancedControls = review.settings != null)
    }
    val rows = view?.optJSONArray("timeline").objects().ifEmpty {
        fallback.map { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text)
            .put("createdAt", it.createdAt).put("streaming", it.streaming).put("rev", it.rev) }
    }.asReversed()
    val contentRevision = rows.map { Triple(it.optString("id"), it.optString("rev"), it.optString("text")) }
    val matches = remember(contentRevision, query) { transcriptMatches(rows, query) }
    val activeMatch = matches.indexOfFirst { it.rowId == selectedMatch && it.offset == matchOffset }
    val list = rememberSaveable(threadKey, saver = readingAnchorSaver(rows)) {
        val byKey = initialPosition?.let { position -> rows.indexOfFirst { it.optString("id") == position.rowId } } ?: -1
        LazyListState(if (byKey >= 0) byKey else initialPosition?.index?.coerceIn(0, (rows.size - 1).coerceAtLeast(0)) ?: 0,
            initialPosition?.offset ?: 0)
    }
    var pendingRestore by remember(threadKey) {
        mutableStateOf(initialPosition.takeIf { refreshing && it != null && rows.none { row -> row.optString("id") == it.rowId } })
    }
    LaunchedEffect(contentRevision, refreshing) {
        val anchor = pendingRestore ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.optString("id") == anchor.rowId }
        if (index >= 0) {
            list.scrollToItem(index, anchor.offset)
            pendingRestore = null
        } else if (!refreshing) pendingRestore = null
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
            if (scrolling) { wasScrolling = true; pendingRestore = null }
            else if (wasScrolling) {
                followingLatest = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset <= 80
                wasScrolling = false
            }
        }
    }
    LaunchedEffect(followLatestRequest) {
        if (followLatestRequest != handledFollowRequest) {
            pendingRestore = null
            handledFollowRequest = followLatestRequest
            followingLatest = true
            list.requestScrollToItem(0)
        }
    }
    // Native row revisions cover streamed blocks as well as plain fallback text.
    LaunchedEffect(contentRevision, followingLatest) {
        if (followingLatest && !list.isScrollInProgress) list.requestScrollToItem(0)
    }
    val savePosition by androidx.compose.runtime.rememberUpdatedState(onPosition)
    fun position() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }?.let {
        dev.avillagran.ot3android.core.data.prefs.ReadingPosition(it.key as? String ?: "", list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
    }
    androidx.compose.runtime.LaunchedEffect(list, threadKey) {
        androidx.compose.runtime.snapshotFlow { if (pendingRestore == null) position() else null }.debounce(250).distinctUntilChanged().collect { it?.let(savePosition) }
    }
    androidx.compose.runtime.DisposableEffect(list, threadKey) {
        onDispose { if (pendingRestore == null) position()?.let(savePosition) }
    }
    val scope = rememberCoroutineScope()
    val away by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 80 } }
    val searchControls: @Composable () -> Unit = {
        OutlinedTextField(query, { query = it; selectedMatch = null; matchOffset = 0 },
            label = { Text(stringResource(R.string.transcript_find_loaded)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("transcript_query"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.transcript_matches, activeMatch + 1, matches.size), modifier = Modifier.weight(1f))
            for (direction in listOf(-1, 1)) TextButton(enabled = matches.isNotEmpty(), onClick = {
                val next = if (activeMatch < 0) { if (direction > 0) 0 else matches.lastIndex }
                    else Math.floorMod(activeMatch + direction, matches.size)
                val match = matches[next]
                selectedMatch = match.rowId; matchOffset = match.offset; followingLatest = false
                scope.launch { list.scrollToItem(rows.indexOfFirst { it.optString("id") == match.rowId }.coerceAtLeast(0)) }
            }, modifier = Modifier.testTag(if (direction < 0) "transcript_previous" else "transcript_next")) {
                Text(stringResource(if (direction < 0) R.string.transcript_previous else R.string.transcript_next))
            }
        }
    }
    CompositionLocalProvider(LocalOpenReaderImage provides { openedImage = it.toString() }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // IME windows can leave less height than the navigation chrome itself.
            // Keep the lazy viewport usable; find controls move into a dialog.
            val compactReader = maxHeight < 320.dp || findRequest != null
            Column(Modifier.fillMaxSize()) {
                if (compactReader && findRequest == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { finding = true }, modifier = Modifier.testTag("transcript_find")) {
                        Text(stringResource(R.string.transcript_find))
                    }
                }
                if (!compactReader) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { finding = !finding }, modifier = Modifier.widthIn(max = 160.dp).testTag("transcript_find")) {
                        Text(stringResource(if (finding) R.string.transcript_close_find else R.string.transcript_find))
                    }
                    if (rows.size > 1) Slider(
                        value = list.firstVisibleItemIndex.toFloat().coerceIn(0f, (rows.size - 1).toFloat()),
                        onValueChange = { value -> followingLatest = false; scope.launch { list.scrollToItem(value.toInt()) } },
                        valueRange = 0f..(rows.size - 1).toFloat(),
                        modifier = Modifier.weight(1f).testTag("transcript_minimap"),
                    )
                    Text(stringResource(R.string.transcript_position, (rows.size - list.firstVisibleItemIndex).coerceAtLeast(0), rows.size),
                        style = MaterialTheme.typography.labelSmall)
                }
                // Keep the viewport independent of its visible anchor. Adding/removing a
                // line here changes the lazy list's extent, which can change the anchor
                // again at a row boundary (and make scroll-to-descendant oscillate).
                val preview = if (away) rows.getOrNull(list.firstVisibleItemIndex)?.takeIf {
                    it.optString("role") in setOf("user", "assistant")
                }?.readerText("text").orEmpty() else ""
                if (!compactReader) Text(preview, minLines = 1, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("transcript_position_preview"))
                if (finding && !compactReader) searchControls()
                Box(Modifier.weight(1f)) {
                    LazyColumn(state = list, reverseLayout = true, contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxSize().testTag(ThreadScreenTags.TIMELINE)) {
                        items(rows, key = { it.getString("id") }) { row ->
                            // rev invalidates content, never the item's saveable expansion or reading anchor.
                            Column(Modifier.then(if (finding && row.optString("id") == selectedMatch)
                                Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)) else Modifier)) {
                                val index = rows.indexOf(row)
                                val commentary = row.optString("role") == "assistant" && rows.take(index).asReversed()
                                    .takeWhile { it.optString("role") != "user" }.any { it.optString("role") == "assistant" && it.readerText("text").isNotBlank() }
                                TimelineRow(row, view?.optJSONObject("planView")?.optJSONObject("ready"), actions,
                                    { openedInspector = it.toString() }, { openedDiff = it.toString() }, commentary)
                            }
                        }
                        if (view?.optBoolean("hasMoreHistory") == true) item("history-boundary") {
                            TextButton(onClick = onLoadOlder, modifier = Modifier.testTag("thread_load_older")) {
                                Text(stringResource(R.string.ot3_pr_more))
                            }
                        }
                    }
                    if (away) TextButton(onClick = { scope.launch { followingLatest = true; list.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).background(Ot3Theme.colors.surface).testTag("thread_jump_latest")) {
                        Text(stringResource(R.string.transcript_latest))
                    }
                }
            }
            if (compactReader) {
                if (finding) androidx.compose.ui.window.Dialog(onDismissRequest = { finding = false }) {
                    Surface {
                        Column(Modifier.padding(12.dp)) {
                            if (rows.size > 1) Slider(
                                value = list.firstVisibleItemIndex.toFloat().coerceIn(0f, (rows.size - 1).toFloat()),
                                onValueChange = { value -> followingLatest = false; scope.launch { list.scrollToItem(value.toInt()) } },
                                valueRange = 0f..(rows.size - 1).toFloat(),
                                modifier = Modifier.fillMaxWidth().testTag("transcript_minimap"),
                            )
                            searchControls()
                            TextButton(onClick = { finding = false }) { Text(stringResource(R.string.transcript_close_find)) }
                        }
                    }
                }
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
internal fun TimelineRow(row: JSONObject, readyPlan: JSONObject?, actions: TranscriptActions, onOpenInspector: (JSONObject) -> Unit, onOpenDiff: (JSONObject) -> Unit, commentary: Boolean = false) {
    val role = row.optString("role")
    val id = row.getString("id")
    if (role == "user") {
        UserMessage(row, actions.quote)
        val checkpoint = LocalReaderCheckpoint.current
        if (id in checkpoint.messages) TextButton(onClick = { checkpoint.open(id) }, modifier = Modifier.testTag("checkpoint_preview_$id")) {
            Text(stringResource(R.string.ot3_git_rewind))
        }
        return
    }
    val colors = Ot3Theme.colors
    val typing = stringResource(R.string.thread_message_typing)
    Row(Modifier.fillMaxWidth().padding(bottom = if (role == "activity") 0.dp else if (commentary) 8.dp else 16.dp).testTag(ThreadScreenTags.message(id)).semantics { if (row.optBoolean("streaming")) stateDescription = typing }, horizontalArrangement = Arrangement.Start) {
        Surface(color = colors.background, shape = RoundedCornerShape(0.dp),
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = if (role == "activity") 0.dp else 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (role) {
                    "assistant" -> {
                        if (row.optJSONArray("blocks").objects().isNotEmpty()) MarkdownBlocks(row.optJSONArray("blocks"))
                        else SelectionContainer { Text(row.optString("text")) }
                        MessageAttachments(row)
                    }
                    "activity" -> ActivityBlock(row.getJSONObject("activity"), id, onOpenInspector)
                    "changes" -> ChangesBlock(row.getJSONObject("changes"), id, onOpenDiff)
                    "plan" -> {
                        val plan = row.optJSONObject("plan") ?: JSONObject()
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.transcript_plan), color = colors.accent, style = MaterialTheme.typography.labelSmall)
                                Text(plan.readerText("title"), Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.bodyMedium)
                                CopyText(plan.readerText("markdown"), "plan_$id")
                                SharePlan(plan)
                            }
                            Surface(color = colors.surface, shape = RoundedCornerShape(8.dp)) {
                                Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(9.dp)) {
                                    MarkdownBlocks(plan.optJSONArray("blocks"))
                                }
                            }
                            PlanLifecycle(plan, readyPlan, actions)
                        }
                    }

                    "error" -> Text(row.optJSONObject("error")?.optString("message").orEmpty(), color = MaterialTheme.colorScheme.error)
                    "context" -> {
                        val boundary = row.optJSONObject("context") ?: JSONObject()
                        HorizontalDivider()
                        Text(stringResource(if (boundary.readerText("kind") == "handoff") {
                            if (boundary.optBoolean("active")) R.string.transcript_handoff_pending else R.string.transcript_handoff
                        } else if (boundary.optBoolean("active")) R.string.transcript_compacting else R.string.transcript_compacted), color = if (boundary.optBoolean("failed")) MaterialTheme.colorScheme.error else colors.dim)
                        contextEndpoints(boundary).takeIf(String::isNotBlank)?.let { Text(it, color = colors.dim) }
                        HorizontalDivider()
                    }
                    "subagents" -> row.optJSONArray("subagents").objects().forEach { agent ->
                        Surface(color = colors.surface, shape = RoundedCornerShape(8.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(listOf(agent.readerText("title"), agent.readerText("status")).filter(String::isNotBlank).joinToString(" · "))
                                for (field in listOf("progress", "result")) agent.readerText(field).takeIf(String::isNotBlank)?.let { Text(it) }
                                val target = agent.readerText("childThreadId")
                                if (target.isNotBlank()) actions.openThread?.let { open ->
                                    TextButton(onClick = { open(target) }, modifier = Modifier.testTag("transcript_agent_${agent.readerText("id")}")) {
                                        Text(stringResource(R.string.transcript_open_thread))
                                    }
                                }
                            }
                        }
                    }
                    "secret" -> SecretRequestCard(row.optJSONObject("secret") ?: JSONObject(), actions.secrets)
                    else -> Unit
                }
                if (row.optBoolean("streaming")) Ot3Spinner(Modifier.testTag(ThreadScreenTags.streaming(id)), size = 12.dp)
                if (role == "assistant" && !row.optBoolean("streaming") && !commentary) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CopyText(row.optString("text"), "message_$id")
                        row.readerText("forkRunId").takeIf { it.isNotBlank() }?.let { run ->
                            actions.fork?.let { fork ->
                                dev.avillagran.ot3android.ui.kit.Ot3IconButton("git-fork", stringResource(R.string.chat_fork), { fork(run) },
                                    modifier = Modifier.testTag("chat_fork_$id"))
                            }
                        }
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
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val entries = activity.optJSONArray("entries").objects()
    val grouped = entries.size > 1
    val singleInspector = activity.optJSONObject("inspector") ?: entries.singleOrNull()?.optJSONObject("inspector")
    val colors = Ot3Theme.colors
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (grouped) dev.avillagran.ot3android.ui.kit.Ot3IconButton(
                if (expanded) "chevron-down" else "chevron-right", activityLabel(activity), { expanded = !expanded },
                modifier = Modifier.size(32.dp).testTag("thread_fold_$id"))
            else singleInspector?.let { item ->
                dev.avillagran.ot3android.ui.kit.Ot3IconButton("terminal", stringResource(R.string.ot3_inspector_details),
                    { onOpenInspector(JSONObject(item.toString())) }, modifier = Modifier.size(32.dp).testTag("thread_inspect_${id}_0"))
            }
            if (activity.optBoolean("active")) Ot3Spinner(size = 12.dp)
            Text(activityLabel(activity), style = MaterialTheme.typography.bodySmall,
                color = if (activity.optBoolean("failed")) colors.failed else colors.dim,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (expanded && grouped) Column(Modifier.padding(start = 20.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            entries.forEachIndexed { index, entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    entry.optJSONObject("inspector")?.let { item ->
                        dev.avillagran.ot3android.ui.kit.Ot3IconButton("terminal", stringResource(R.string.ot3_inspector_details),
                            { onOpenInspector(JSONObject(item.toString())) }, modifier = Modifier.size(32.dp).testTag("thread_inspect_${id}_$index"))
                    }
                    Text(activityLabel(entry), style = MaterialTheme.typography.bodySmall, color = colors.dim)
                }
            }
        }
    }
}

@Composable
private fun ChangesBlock(changes: JSONObject, id: String, onOpenDiff: (JSONObject) -> Unit) {
    Fold("${stringResource(R.string.ot3_inspector_label_changes)} +${changes.optInt("additions")} −${changes.optInt("deletions")}", id) {
        TextButton(onClick = { onOpenDiff(changes) }, modifier = Modifier.testTag("thread_diff_$id")) { Text(stringResource(R.string.ot3_open_diff)) }
        changes.optJSONArray("files").objects().forEach { file ->
            SelectionContainer { Text("${file.optString("path")} +${file.optInt("additions")} −${file.optInt("deletions")}", fontFamily = FiraCode, style = MaterialTheme.typography.bodySmall) }
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

internal fun inlineSpans(spans: JSONArray?, mathEnabled: Boolean = true, onReference: ((String) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
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
        val reference = link?.optString("href")?.takeIf { it.isNotEmpty() && !it.startsWith("#") && onReference != null }
        if (reference != null) pushLink(LinkAnnotation.Clickable(reference, linkInteractionListener = { onReference!!(reference) }))
        else if (web != null) pushLink(LinkAnnotation.Url(web))
        withStyle(style) {
            when (span.optString("type")) {
                "math" -> append(if (mathEnabled) dev.avillagran.ot3android.ui.thread.rendering.mathText(span.optString("tex")) else AnnotatedString(span.optString("tex")))
                "break" -> append("\n")
                else -> append(span.optString("text"))
            }
        }
        if (reference != null || web != null) pop()
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun CopyText(text: String, id: String) {
    val clipboard = LocalClipboardManager.current
    dev.avillagran.ot3android.ui.kit.Ot3IconButton("copy", stringResource(R.string.ot3_reader_copy),
        { clipboard.setText(AnnotatedString(text)) }, enabled = text.isNotEmpty(), modifier = Modifier.size(32.dp).testTag("thread_copy_$id"))
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
    var imageEndedLine = false
    Column(Modifier.fillMaxWidth()) {
        spans.objects().forEach { span ->
            if (span.optString("type") == "image") {
                if (run.length() > 0) Text(inlineSpans(run, LocalReaderMath.current, LocalReaderReference.current), style = style, modifier = Modifier.fillMaxWidth())
                run = JSONArray()
                MarkdownImage(span)
                imageEndedLine = true
            } else {
                // The image already occupies its own line. A Markdown soft break
                // immediately after it must not introduce an empty caption line.
                if (!(imageEndedLine && span.optString("type") == "break" && !span.optBoolean("hard"))) run.put(span)
                imageEndedLine = false
            }
        }
        if (run.length() > 0) Text(inlineSpans(run, LocalReaderMath.current, LocalReaderReference.current), style = style, modifier = Modifier.fillMaxWidth())
    }
}
