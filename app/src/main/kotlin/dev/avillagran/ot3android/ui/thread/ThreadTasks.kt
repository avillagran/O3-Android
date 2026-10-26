package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.Ot3Icon
import dev.avillagran.ot3android.ui.navigation.VerticalReveal
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject

/** Counts, current task, order and durations come from timeline.view.tasks, never rebuilt. */
@Composable
internal fun ThreadTasks(tasks: JSONObject?) {
    if (tasks == null || tasks.optInt("total") <= 0) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val colors = Ot3Theme.colors
    val rows = tasks.optJSONArray("items").objects()
    Surface(color = colors.card, border = BorderStroke(1.dp, colors.cardBorder), shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("thread_tasks")) {
        Column {
            BoxWithConstraints {
                val wide = maxWidth >= 560.dp
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded }
                    .testTag("thread_tasks_toggle").padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ot3Icon("list-todo", null, size = 16.dp, tint = colors.dim)
                    Text(stringResource(R.string.tasks_title), color = colors.dim, style = MaterialTheme.typography.labelMedium)
                    Text(tasks.optString("current"), Modifier.weight(1f).alpha(0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = colors.foreground, style = MaterialTheme.typography.labelMedium)
                    Text("${tasks.optInt("completed")}/${tasks.optInt("total")}",
                        color = if (tasks.optInt("completed") >= tasks.optInt("total")) colors.done else colors.dim,
                        style = MaterialTheme.typography.labelMedium)
                    if (wide && tasks.optInt("total") in 2..10) Row(Modifier.width(80.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        rows.forEach { item ->
                            Surface(color = when (item.optString("status")) {
                                "completed" -> colors.done
                                "running" -> colors.accent
                                else -> colors.dim.copy(alpha = 0.45f)
                            }, shape = RoundedCornerShape(2.dp), modifier = Modifier.weight(1f).height(3.dp)) {}
                        }
                    }
                    Ot3Icon(if (expanded) "chevron-up" else "chevron-down", null, size = 14.dp, tint = colors.dim)
                }
            }
            VerticalReveal(expanded) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).testTag("thread_tasks_list")) {
                    items(rows, key = { it.getString("id") }) { item ->
                        val status = item.optString("status")
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Ot3Icon(when (status) { "completed" -> "check"; "running" -> "circle-dot"; else -> "circle" },
                                contentDescription = null, size = 14.dp, tint = when (status) { "completed" -> colors.done; "running" -> colors.accent; else -> colors.dim })
                            Text(item.optString("text"), Modifier.weight(1f).alpha(when (status) { "completed" -> 0.55f; "running" -> 0.9f; else -> 0.7f }), color = colors.foreground,
                                style = MaterialTheme.typography.bodySmall)
                            val duration = if (item.has("durationMs")) {
                                val seconds = item.optLong("durationMs") / 1000
                                if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
                            } else if (status == "running") stringResource(R.string.tasks_now) else ""
                            Text(duration, color = colors.dim, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
