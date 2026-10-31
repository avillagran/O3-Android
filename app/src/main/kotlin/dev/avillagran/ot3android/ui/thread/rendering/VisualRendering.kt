package dev.avillagran.ot3android.ui.thread.rendering

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.FiraCode
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.thread.*
import org.json.JSONObject

/** P20 supplies authoritative command eligibility and handles terminal routing. */
internal data class CodeActions(val run: ((JSONObject) -> Unit)? = null)
internal val LocalCodeActions = staticCompositionLocalOf { CodeActions() }

@Composable
internal fun VisualCodeBlock(block: JSONObject, index: Int, streaming: Boolean = false) {
    val code = block.optString("code")
    val language = block.optString("language")
    val kind = when (language.lowercase()) { "mermaid" -> VisualKind.Mermaid; "qml" -> VisualKind.Qml; else -> null }
    var preview by remember(code, language) { mutableStateOf(false) }
    val actions = LocalCodeActions.current
    Surface(color = Ot3Theme.colors.surface, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(language, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                CopyText(code, "code_$index")
            }
            Row {
                if (kind != null && !streaming) TextButton(onClick = { preview = !preview }, modifier = Modifier.testTag("visual_toggle_$index")) {
                    Text(stringResource(if (preview) R.string.ot3_render_show_code else R.string.ot3_render_show_preview))
                }
                if (actions.run != null && block.opt("command") is String && !streaming) TextButton(onClick = { actions.run.invoke(block) }) {
                    Text(stringResource(R.string.ot3_render_run))
                }
            }
            if (preview && kind != null) VisualPreview(VisualRequest(kind, code, language), "code_$index")
            else SelectionContainer {
                val colors = Ot3Theme.colors
                val highlighted = remember(code, language, colors) { highlightedCode(code, language, colors.accent, colors.dim, colors.done) }
                Text(highlighted, fontFamily = FiraCode, fontSize = 12.sp, softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).testTag("thread_code_$index"))
            }
        }
    }
}

@Composable
internal fun VisualMath(tex: String, display: Boolean = true, enabled: Boolean = true) {
    val text = remember(tex, enabled) { if (enabled) mathText(tex) else androidx.compose.ui.text.AnnotatedString(tex) }
    SelectionContainer {
        Text(text, fontFamily = if (enabled) FontFamily.Serif else FiraCode,
            fontStyle = if (enabled) FontStyle.Italic else FontStyle.Normal,
            fontSize = if (display) 18.sp else 14.sp, textAlign = if (display) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("visual_math"))
    }
}
