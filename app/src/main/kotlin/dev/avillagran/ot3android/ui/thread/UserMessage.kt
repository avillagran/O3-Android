package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.ui.platform.LocalConfiguration

/** ot3's own prompt lane: compact bubble, with touch-accessible actions below it. */
@Composable
internal fun UserMessage(row: JSONObject, onQuote: ((String) -> Unit)? = null) {
    val id = row.getString("id")
    val author = stringResource(R.string.ot3_reader_you)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag(ThreadScreenTags.message(id))) {
        val bubbleMaxWidth = maxWidth * .8f
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            val attribution = when (row.optString("attribution")) {
                "automation" -> R.string.ot3_reader_automation
                "t3code" -> R.string.ot3_reader_t3code
                "agent" -> R.string.ot3_reader_agent
                else -> null
            }
            attribution?.let { Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim) }
            Surface(
                color = Ot3Theme.colors.surface,
                contentColor = Ot3Theme.colors.foreground,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(min = 80.dp, max = bubbleMaxWidth)
                    .testTag("thread_bubble_$id").semantics { contentDescription = author },
            ) {
                SelectionContainer {
                    Text(row.optString("text"), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            MessageAttachments(row)
            if (!row.optBoolean("streaming")) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MessageDate(row.optLong("createdAt"))
                    CopyText(row.optString("text"), "message_$id")
                    if (row.optString("text").isNotBlank()) onQuote?.let { quote ->
                        dev.avillagran.ot3android.ui.kit.Ot3IconButton("message-square", stringResource(R.string.transcript_quote),
                            { quote(row.optString("text")) }, modifier = Modifier.size(32.dp).testTag("transcript_quote_$id"))
                    }
                }
            }
        }
    }
}

@Composable
internal fun MessageDate(timestamp: Long) {
    if (timestamp > 0) Text(
        SimpleDateFormat("dd MMM · HH:mm", LocalConfiguration.current.locales[0]).format(Date(timestamp)),
        style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim,
    )
}
