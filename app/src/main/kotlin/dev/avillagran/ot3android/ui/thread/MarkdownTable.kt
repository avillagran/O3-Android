package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONArray
import org.json.JSONObject

/** Cells, inline formatting and both clipboard formats come unchanged from Rust. */
@Suppress("DEPRECATION")
@Composable
internal fun MarkdownTable(block: JSONObject, index: Int) {
    val header = block.optJSONArray("header") ?: return
    val columns = header.length()
    if (columns == 0) return
    val rows = block.optJSONArray("rows") ?: JSONArray()
    val alignments = block.optJSONArray("alignments")
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columnWidth = (maxWidth / columns).coerceIn(112.dp, 240.dp)
            SelectionContainer {
                Column(Modifier.horizontalScroll(rememberScrollState()).testTag("thread_table_$index")) {
                    for (row in -1 until rows.length()) {
                        val cells = if (row == -1) header else rows.optJSONArray(row) ?: JSONArray()
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            for (column in 0 until columns) {
                                Box(Modifier.width(columnWidth).padding(horizontal = 12.dp, vertical = 7.dp)) {
                                    MarkdownInlineContent(cells.optJSONArray(column), style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        fontWeight = if (row == -1) FontWeight.Bold else FontWeight.Normal,
                                        textAlign = when (alignments?.optString(column)) {
                                            "center" -> TextAlign.Center
                                            "right" -> TextAlign.Right
                                            else -> TextAlign.Left
                                        },
                                    ))
                                }
                            }
                        }
                        HorizontalDivider(Modifier.width(columnWidth * columns), color = Ot3Theme.colors.divider)
                    }
                }
            }
        }
        Row {
            listOf("markdown" to R.string.ot3_copy_table_markdown, "csv" to R.string.ot3_copy_table_csv).forEach { (format, label) ->
                val text = block.optString(format)
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(text)) },
                    enabled = text.isNotEmpty(),
                    modifier = Modifier.testTag("thread_copy_table_${format}_$index"),
                ) { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
