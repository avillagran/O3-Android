package dev.avillagran.ot3android.ui.settings.storage

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.avillagran.ot3android.ui.settings.maintenance.maintenanceText
import org.json.JSONObject

/** Only user intent is assembled here; native scopedSettings validates and folds overrides. */
@Composable
internal fun ProjectWorktreePolicy(field: JSONObject, enabled: Boolean, change: (Any?) -> Unit) {
    val value = field.optJSONObject("value")
    val mode = field.optString("mode", "inherit")
    Text(maintenanceText(when (mode) { "off" -> "Off"; "custom" -> "Custom"; else -> "Inherit" }))
    TextButton(enabled = enabled, onClick = { change(null) }) { Text(maintenanceText("Use inherited value")) }
    TextButton(enabled = enabled, onClick = { change(JSONObject().put("mode", "off")) }) { Text(maintenanceText("Off")) }
    TextButton(enabled = enabled, onClick = { change(JSONObject().put("mode", "custom").put("rules", JSONObject())) }) { Text(maintenanceText("Custom")) }
    if (mode != "custom") return
    val rules = field.optJSONObject("rules") ?: return
    val labels = mapOf("worktreeOnDelete" to "Delete worktrees with deleted threads", "worktreeAfterDays" to "Delete inactive worktrees",
        "worktreeSettledAfterDays" to "Delete settled worktrees", "worktreeOnMerge" to "Delete merged worktrees",
        "worktreeUnchanged" to "Delete unchanged worktrees", "worktreeKeepWhen" to "Keep worktrees with local changes")
    labels.forEach { (key, label) ->
        if (!rules.has(key)) return@forEach
        fun update(next: Any?) {
            val overrides = value?.optJSONObject("rules")?.let { JSONObject(it.toString()) } ?: JSONObject()
            overrides.put(key, next ?: JSONObject.NULL)
            change(JSONObject().put("mode", "custom").put("rules", overrides))
        }
        Column {
            Text(maintenanceText(label))
            when {
                key.endsWith("Days") -> {
                    var edit by remember(key, rules.opt(key)?.toString()) { mutableStateOf(if (rules.isNull(key)) "" else rules.optString(key)) }
                    OutlinedTextField(edit, { edit = it }, enabled = enabled, label = { Text(maintenanceText(label)) })
                    val min = if (key == "worktreeSettledAfterDays") 0 else 1
                    TextButton(enabled = enabled && edit.toIntOrNull()?.let { it in min..3650 } == true,
                        onClick = { update(edit.toInt()) }) { Text(maintenanceText("Save")) }
                    TextButton(enabled = enabled, onClick = { update(null) }) { Text(maintenanceText("Off")) }
                }
                key == "worktreeKeepWhen" -> {
                    listOf("any-local-files" to "Any local files", "uncommitted-changes" to "Uncommitted changes", "tracked-changes" to "Edited tracked files").forEach { (id, name) ->
                        TextButton(enabled = enabled, onClick = { update(id) }) { Text((if (rules.optString(key) == id) "✓ " else "") + maintenanceText(name)) }
                    }
                }
                else -> {
                    Text(if (rules.optBoolean(key)) maintenanceText("On") else maintenanceText("Off"))
                    TextButton(enabled = enabled, onClick = { update(true) }) { Text(maintenanceText("On")) }
                    TextButton(enabled = enabled, onClick = { update(false) }) { Text(maintenanceText("Off")) }
                }
            }
        }
    }
}
