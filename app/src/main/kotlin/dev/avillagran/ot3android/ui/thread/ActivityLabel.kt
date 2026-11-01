package dev.avillagran.ot3android.ui.thread

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.thread.reader.readerText
import org.json.JSONObject

/** Desktop T3Backend.activityText presentation, using the same localized vocabulary. */
@Composable
internal fun activityLabel(activity: JSONObject): String {
    val category = activity.readerText("category").ifBlank {
        when (activity.readerText("type")) {
            "reasoning" -> "think"
            "command_execution" -> "execute"
            "file_change" -> "edit"
            "file_search" -> "search"
            "web_search" -> "web"
            "subagent" -> "agent"
            else -> "tool"
        }
    }
    val live = activity.optBoolean("active", activity.readerText("status") !in setOf("completed", "failed", "cancelled"))
    val count = activity.optInt("count", activity.optJSONArray("entries")?.length() ?: 1)
    val failed = activity.optBoolean("failed") || activity.readerText("status") == "failed"
    val command = activity.readerText("command")
    val title = activity.readerText("title")
    val label = when {
        failed && !live && count <= 1 -> when (category) {
            "execute" -> R.string.chat_activity_16
            "read" -> R.string.chat_activity_17
            "edit" -> R.string.chat_activity_18
            "search" -> R.string.chat_activity_19
            "web" -> R.string.chat_activity_20
            "agent" -> R.string.chat_activity_21
            "compact" -> R.string.chat_activity_23
            else -> R.string.chat_activity_22
        }
        category == "think" -> R.string.chat_activity_0
        category == "compact" -> if (live) R.string.chat_activity_1 else R.string.chat_activity_2
        category == "execute" -> if (live) R.string.chat_activity_3 else R.string.chat_activity_4
        category == "read" -> if (live) R.string.chat_activity_5 else R.string.chat_activity_6
        category == "edit" -> if (live) R.string.chat_activity_7 else R.string.chat_activity_8
        category == "search" -> if (live) R.string.chat_activity_9 else R.string.chat_activity_10
        category == "web" -> if (live) R.string.chat_activity_11 else R.string.chat_activity_12
        category == "agent" -> R.string.chat_activity_13
        else -> if (live) R.string.chat_activity_14 else R.string.chat_activity_15
    }
    if (command.isNotBlank() && (live || count <= 1) && !(failed && !live)) {
        return stringResource(if (live) R.string.chat_activity_24 else R.string.chat_activity_25, command)
    }
    var text = stringResource(label)
    val detail = if (failed && !live && count <= 1) command.ifBlank { title } else title
    if (detail.isNotBlank()) text = stringResource(R.string.chat_activity_26, text, detail)
    else if (live) text += "…"
    if (count > 1) text = stringResource(R.string.chat_activity_27, text, count)
    val failures = activity.optJSONArray("entries").objects().count { it.optBoolean("failed") }
    if (count > 1 && failures > 0) text = stringResource(R.string.chat_activity_28, text, failures)
    return text
}
