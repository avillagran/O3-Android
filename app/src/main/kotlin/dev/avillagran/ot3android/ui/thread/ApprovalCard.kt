package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.Ot3Sheet
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONArray
import org.json.JSONObject

/** First native pending approval, immediately above the composer, as T3Approvals.qml. */
@Composable
internal fun ApprovalCard(
    approvals: JSONArray?, enabled: Boolean, responding: String?, answered: Set<String>,
    onDecision: (String, String) -> Unit,
) {
    val approval = approvals?.optJSONObject(0) ?: return
    val id = approval.getString("requestId")
    var more by rememberSaveable(id) { mutableStateOf(false) }
    val choices = approval.optJSONArray("options").objects()
    val live = approval.optString("responseCapability") == "live"
    val canRespond = enabled && live && responding == null && id !in answered
    val colors = Ot3Theme.colors
    val kind = when (approval.optString("requestKind")) {
        "command" -> R.string.approval_command
        "file-read" -> R.string.approval_file_read
        "file-change" -> R.string.approval_file_change
        "permission" -> R.string.approval_permission
        "mcp-elicitation" -> R.string.approval_app_access
        else -> R.string.approval_required
    }
    Surface(
        color = colors.card, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().testTag(ThreadScreenTags.request(id)),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(kind), Modifier.weight(1f), color = colors.accent, style = MaterialTheme.typography.labelLarge)
                if (approvals.length() > 1) Text("1/${approvals.length()}", color = colors.dim)
            }
            approval.optString("appName").takeIf { it.isNotBlank() }?.let {
                Text(it, color = colors.dim, style = MaterialTheme.typography.labelMedium)
            }
            SelectionContainer {
                Text(
                    if (live) approval.optString("detail").ifBlank { stringResource(kind) } else stringResource(R.string.approval_not_resumable),
                    Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState()),
                    color = colors.foreground, style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (approval.optString("requestKind") == "mcp-elicitation") null else FontFamily.Monospace,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.filter { it.optString("decision") in listOf("accept", "decline") }.forEach { choice ->
                    OutlinedButton(onClick = { onDecision(id, choice.getString("decision")) }, enabled = canRespond) {
                        Text(choiceLabel(choice))
                    }
                }
            }
            val extra = choices.filter { it.optString("decision") !in listOf("accept", "decline") }
            if (extra.isNotEmpty()) TextButton(onClick = { more = true }, enabled = canRespond) {
                Text(stringResource(R.string.approval_more))
            }
            when {
                responding == id -> Text(stringResource(R.string.approval_sending), color = colors.dim)
                id in answered -> Text(stringResource(R.string.approval_answered), color = colors.dim)
            }
        }
    }
    if (more) Ot3Sheet(onDismissRequest = { more = false }, title = stringResource(R.string.approval_more)) {
        choices.filter { it.optString("decision") !in listOf("accept", "decline") }.forEach { choice ->
            TextButton(onClick = { more = false; onDecision(id, choice.getString("decision")) }, enabled = canRespond,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column {
                    Text(choiceLabel(choice))
                    choice.optString("warning").takeIf { it.isNotBlank() }?.let {
                        Text(it, color = colors.dim, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun choiceLabel(choice: JSONObject): String =
    (if (choice.optString("warning").isNotBlank()) "⚠ " else "") + choice.optString("label")
