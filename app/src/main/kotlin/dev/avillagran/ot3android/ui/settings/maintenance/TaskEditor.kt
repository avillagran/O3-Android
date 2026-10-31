package dev.avillagran.ot3android.ui.settings.maintenance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import org.json.JSONArray
import org.json.JSONObject

/** Native schedule/model/workspace validation remains in the pinned typed API. */
@Composable
internal fun TaskEditor(task: JSONObject, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var title by remember { mutableStateOf(task.optString("title")) }
    var prompt by remember { mutableStateOf(task.optString("prompt")) }
    val originalSchedule = task.optJSONObject("schedule") ?: JSONObject()
    var type by remember { mutableStateOf(originalSchedule.optString("type", "interval")) }
    var minutes by remember { mutableStateOf((originalSchedule.optLong("everyMs", 3600000) / 60000).toString()) }
    var time by remember { mutableStateOf(originalSchedule.optString("timeOfDay", "09:00")) }
    var days by remember { mutableStateOf(originalSchedule.optJSONArray("weekdays")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()) }
    val originalWorkspace = task.optJSONObject("workspaceStrategy") ?: JSONObject()
    var workspace by remember { mutableStateOf(originalWorkspace.optString("type", "root")) }
    var base by remember { mutableStateOf(originalWorkspace.optString("baseRef")) }
    var path by remember { mutableStateOf(originalWorkspace.optString("worktreePath")) }
    val scheduleValid = when(type) {
        "interval" -> minutes.toLongOrNull()?.let { it in 1..525600 } == true
        "fixed_time" -> Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(time)
        "webhook" -> true
        else -> false
    }
    val workspaceValid = when(workspace) { "root" -> true; "worktree" -> base.isNotBlank(); "existing_worktree" -> path.isNotBlank(); else -> false }
    val valid = title.isNotBlank() && prompt.isNotBlank() && scheduleValid && workspaceValid &&
        task.optString("projectId").isNotBlank() && task.optJSONObject("modelSelection") != null
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.maintenance_edit)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.maintenance_title)) })
            OutlinedTextField(prompt, { prompt = it }, label = { Text(stringResource(R.string.maintenance_prompt)) })
            listOf("interval" to "Interval", "fixed_time" to "Time of day", "webhook" to "Webhook").forEach { (id, label) ->
                TextButton(onClick = { type = id }) { Text((if (type == id) "✓ " else "") + maintenanceText(label)) }
            }
            if (type == "interval") OutlinedTextField(minutes, { minutes = it }, label = { Text(stringResource(R.string.maintenance_minutes)) })
            if (type == "fixed_time") {
                OutlinedTextField(time, { time = it }, label = { Text(maintenanceText("Time of day") + " (HH:mm)") })
                listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEachIndexed { day, label ->
                    TextButton(onClick = { days = if (day in days) days - day else days + day }) { Text((if (day in days) "✓ " else "") + maintenanceText(label)) }
                }
                if (days.isEmpty()) Text(maintenanceText("Every day"))
            }
            if (type == "webhook") Text(maintenanceText("Runs on each request to the task's webhook URL, shown after saving. The prompt may use {{body.path}} placeholders."))
            listOf("root" to "Local", "worktree" to "Worktree", "existing_worktree" to "Existing worktree").forEach { (id, label) ->
                TextButton(onClick = { workspace = id }) { Text((if (workspace == id) "✓ " else "") + maintenanceText(label)) }
            }
            if (workspace == "worktree") OutlinedTextField(base, { base = it }, label = { Text(maintenanceText("Base branch")) })
            if (workspace == "existing_worktree") OutlinedTextField(path, { path = it }, label = { Text(maintenanceText("Worktree path")) })
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = {
        val args = JSONObject()
        listOf("id", "enabled", "projectId", "modelSelection", "runtimeMode", "interactionMode", "threadId").forEach { key ->
            if (task.has(key)) args.put(key, task.get(key))
        }
        args.put("title", title.trim()).put("prompt", prompt.trim())
        if (task.has("id")) args.put("requireExisting", true)
        else args.put("commandId", "maintenance-task-${java.util.UUID.randomUUID()}")
        val schedule = when(type) {
            "interval" -> JSONObject().put("type", type).put("everyMs", minutes.toLong() * 60000)
            "fixed_time" -> JSONObject().put("type", type).put("timeOfDay", time).apply {
                if (days.isNotEmpty() && days.size < 7) put("weekdays", JSONArray(days.sorted()))
            }
            else -> if (originalSchedule.optString("type") == "webhook") JSONObject(originalSchedule.toString()) else JSONObject().put("type", "webhook")
        }
        val strategy = if (workspace == originalWorkspace.optString("type")) JSONObject(originalWorkspace.toString()) else JSONObject().put("type", workspace)
        if (workspace == "worktree") strategy.put("baseRef", base.trim())
        if (workspace == "existing_worktree") strategy.put("worktreePath", path.trim())
        onSave(args.put("schedule", schedule).put("workspaceStrategy", strategy))
    }) { Text(stringResource(R.string.maintenance_save)) } }, dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.maintenance_cancel)) }
    })
}
