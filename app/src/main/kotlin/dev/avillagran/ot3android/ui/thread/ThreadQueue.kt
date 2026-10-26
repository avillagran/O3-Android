package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.Ot3Sheet
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject

/** Displays queue.derive's order and capabilities without reconstructing provider rules. */
@Composable
internal fun ThreadQueue(
    queue: JSONObject?,
    enabled: Boolean,
    onCommand: (JSONObject) -> Unit,
    onEdit: (JSONObject) -> Unit,
    editingRunId: String? = null,
) {
    val array = queue?.optJSONArray("items") ?: return
    val rows = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    if (rows.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = Ot3Theme.colors
    Surface(
        color = colors.card,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().testTag("thread_queue"),
    ) {
        Row(
            Modifier.clickable { expanded = true }.padding(horizontal = 12.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (queue.optBoolean("held")) R.string.queue_paused else R.string.queue_label, rows.size),
                Modifier.weight(1f), color = colors.dim, style = MaterialTheme.typography.labelMedium,
            )
            if (queue.optBoolean("held")) TextButton(
                onClick = { onCommand(JSONObject().put("action", "resume")) }, enabled = enabled,
            ) { Text(stringResource(R.string.queue_resume)) }
        }
    }
    if (expanded) Ot3Sheet(onDismissRequest = { expanded = false }, title = stringResource(R.string.queue_title)) {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(rows, key = { _, row -> row.getString("runId") }) { index, row ->
                val run = row.getString("runId")
                val rowEnabled = enabled && !row.optBoolean("pending")
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        row.optString("text").ifBlank { stringResource(R.string.queue_unavailable) },
                        color = colors.foreground, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onEdit(row); expanded = false },
                            enabled = rowEnabled && row.optBoolean("available", true) && editingRunId == null,
                        ) { Text(stringResource(R.string.queue_edit)) }
                        TextButton(onClick = { onCommand(JSONObject().put("action", "cancel").put("runId", run)) },
                            enabled = rowEnabled && editingRunId != run,
                        ) { Text(stringResource(R.string.queue_remove)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (queue.optBoolean("canSteer")) TextButton(onClick = {
                            onCommand(JSONObject().put("action", "steer").put("runId", run)
                                .put("targetRunId", queue.getString("activeRunId")))
                        }, enabled = rowEnabled) { Text(stringResource(R.string.queue_steer)) }
                        if (index > 0 && queue.optBoolean("canReorder")) TextButton(onClick = {
                            onCommand(JSONObject().put("action", "reorder").put("runId", run)
                                .put("beforeRunId", rows[index - 1].getString("runId")))
                        }, enabled = rowEnabled) { Text(stringResource(R.string.queue_move_up)) }
                    }
                }
            }
        }
    }
}
