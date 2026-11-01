package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.Ot3Sheet
import dev.avillagran.ot3android.ui.kit.Ot3Icon
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject

private data class QueueDragInputs(val rows: List<Pair<String, Boolean>>, val enabled: Boolean, val canReorder: Boolean, val editing: String?, val owner: Any?, val orientation: Int)
private data class QueueDrag(val run: String, val source: Int, val target: Int, val y: Float, val inputs: QueueDragInputs)

/** Server order stays untouched; pointer midpoints choose an input for native queue.command. */
@Composable
internal fun ThreadQueue(queue: JSONObject?, enabled: Boolean, onCommand: (JSONObject) -> Unit,
    onEdit: (JSONObject) -> Unit, editingRunId: String? = null, dragOwner: Any? = null,
    feedback: @Composable () -> Unit = {},
) {
    val rows = queue?.optJSONArray("items").objects()
    if (rows.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = Ot3Theme.colors
    Surface(color = colors.card, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().testTag("thread_queue")) {
        Row(Modifier.clickable { expanded = true }.padding(horizontal = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (queue!!.optBoolean("held")) R.string.queue_paused else R.string.queue_label, rows.size), color = colors.dim, style = MaterialTheme.typography.labelSmall)
                Text(rows.first().optString("text"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            if (queue?.optBoolean("held") == true) TextButton(onClick = { onCommand(JSONObject().put("action", "resume")) }, enabled = enabled) { Text(stringResource(R.string.queue_resume)) }
        }
    }
    if (expanded) Ot3Sheet(onDismissRequest = { expanded = false }, title = stringResource(R.string.queue_title)) {
        Column(Modifier.fillMaxWidth().testTag("queue_feedback")) { feedback() }
        val list = rememberLazyListState()
        val inputs = QueueDragInputs(rows.map { it.getString("runId") to it.optBoolean("pending") }, enabled, queue!!.optBoolean("canReorder"), editingRunId, dragOwner, LocalConfiguration.current.orientation)
        val latestInputs by rememberUpdatedState(inputs)
        val command by rememberUpdatedState(onCommand)
        var drag by remember { mutableStateOf<QueueDrag?>(null) }
        var listTop by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(inputs) { drag = null }
        fun target(y: Float): Int {
            val visible = list.layoutInfo.visibleItemsInfo
            return (visible.firstOrNull { y < it.offset + it.size / 2f }?.index ?: (visible.lastOrNull()?.index?.plus(1) ?: 0)).coerceIn(0, rows.size)
        }
        fun reorder(run: String, before: String?) { command(JSONObject().put("action", "reorder").put("runId", run).put("beforeRunId", before ?: JSONObject.NULL)) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).onGloballyPositioned { listTop = it.positionInRoot().y }.testTag("queue_list"), state = list) {
            itemsIndexed(rows, key = { _, row -> row.getString("runId") }) { index, row ->
                val run = row.getString("runId")
                val rowEnabled = enabled && !row.optBoolean("pending")
                val reorderEnabled = rowEnabled && inputs.canReorder
                var menu by remember(run, inputs) { mutableStateOf(false) }
                var gripTop by remember(run) { mutableFloatStateOf(0f) }
                val up = stringResource(R.string.queue_move_up)
                val down = stringResource(R.string.ot3_queue_move_down)
                Box(Modifier.fillMaxWidth().testTag("queue_row_$run").background(if (editingRunId == run) colors.accent.copy(alpha = .13f) else androidx.compose.ui.graphics.Color.Transparent)) {
                    Row(Modifier.fillMaxWidth().alpha(if (drag?.run == run) .5f else 1f).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box {
                            Ot3IconButton("grip-vertical", stringResource(R.string.ot3_queue_reorder), { menu = true }, enabled = reorderEnabled,
                                tint = colors.dim, modifier = Modifier.testTag("queue_grip_$run").onGloballyPositioned { gripTop = it.positionInRoot().y }
                                    .semantics { customActions = buildList {
                                        if (reorderEnabled && index > 0) add(CustomAccessibilityAction(up) { reorder(run, rows[index - 1].getString("runId")); true })
                                        if (reorderEnabled && index < rows.lastIndex) add(CustomAccessibilityAction(down) { reorder(run, rows.getOrNull(index + 2)?.getString("runId")); true })
                                    } }
                                    .pointerInput(inputs, run) {
                                        if (reorderEnabled) detectDragGestures(
                                            onDragStart = { offset ->
                                                menu = false
                                                val y = gripTop + offset.y - listTop
                                                drag = QueueDrag(run, index, target(y), y, inputs)
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                drag?.takeIf { it.inputs == latestInputs }?.let { val y = gripTop + change.position.y - listTop; drag = it.copy(y = y, target = target(y)) }
                                            },
                                            onDragCancel = { drag = null },
                                            onDragEnd = {
                                                val dropped = drag
                                                drag = null
                                                if (dropped != null && dropped.inputs == latestInputs && dropped.target != dropped.source && dropped.target != dropped.source + 1) {
                                                    reorder(dropped.run, rows.getOrNull(dropped.target)?.getString("runId"))
                                                }
                                            },
                                        )
                                    })
                            DropdownMenu(menu && reorderEnabled, { menu = false }) {
                                if (index > 0) DropdownMenuItem(text = { Text(up) }, onClick = { menu = false; reorder(run, rows[index - 1].getString("runId")) })
                                if (index < rows.lastIndex) DropdownMenuItem(text = { Text(down) }, onClick = { menu = false; reorder(run, rows.getOrNull(index + 2)?.getString("runId")) })
                            }
                        }
                        if (row.optBoolean("pending")) Ot3Icon("clock", null, tint = colors.dim, size = 12.dp)
                        if (row.optJSONArray("attachments")?.length() ?: 0 > 0) Ot3Icon("paperclip", null, tint = colors.dim, size = 14.dp)
                        Text(row.optString("text").ifBlank { row.optJSONArray("attachments").objects().joinToString(", ") { it.optString("name") }.ifBlank { stringResource(R.string.queue_unavailable) } }, Modifier.weight(1f), color = colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Ot3IconButton("square-pen", stringResource(R.string.queue_edit), { onEdit(row); expanded = false }, enabled = rowEnabled && row.optBoolean("available", true) && editingRunId == null, modifier = Modifier.testTag("queue_edit_$run"))
                        if (queue.optBoolean("canSteer")) Ot3IconButton("corner-up-right", stringResource(R.string.queue_steer), { onCommand(JSONObject().put("action", "steer").put("runId", run).put("targetRunId", queue.getString("activeRunId"))) }, enabled = rowEnabled, modifier = Modifier.testTag("queue_steer_$run"))
                        Ot3IconButton("x", stringResource(R.string.queue_remove), { onCommand(JSONObject().put("action", "cancel").put("runId", run)) }, enabled = rowEnabled && editingRunId != run, modifier = Modifier.testTag("queue_remove_$run"))
                    }
                    if (drag?.target == index) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(colors.accent).testTag("queue_insert_$index"))
                    if (index == rows.lastIndex && drag?.target == rows.size) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(colors.accent).testTag("queue_insert_${rows.size}"))
                }
            }
        }
    }
}
