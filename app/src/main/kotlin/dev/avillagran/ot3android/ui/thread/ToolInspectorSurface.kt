package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.ui.kit.Ot3Spinner
import dev.avillagran.ot3android.ui.kit.Ot3Button
import dev.avillagran.ot3android.ui.kit.Ot3ButtonStyle
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.ot3android.ui.theme.FiraCode
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

private data class InspectorRead(val loading: Boolean = true, val failed: Boolean = false, val view: JSONObject? = null)

/** Reader-owned window: lazy-row recycling cannot dispose its selected inspector. */
@Composable
internal fun ToolInspectorDialog(reference: JSONObject, onInspect: suspend (JSONObject) -> JSONObject?, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val view = androidx.compose.ui.platform.LocalView.current
        val dim = Ot3Theme.colors.scrim.alpha
        SideEffect { (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(dim) }
        BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        Surface(Modifier.heightIn(max = maxHeight * 0.9f).safeDrawingPadding().testTag("thread_inspector_window"),
            color = Ot3Theme.colors.background, contentColor = Ot3Theme.colors.foreground,
            shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Ot3Theme.colors.divider)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)
                .testTag("thread_inspector_scroll")) {
                ToolInspectorSurface(reference, onInspect, onClose)
            }
        }
        }
    }
}

/** Displays only native toolInspector.parse fields. Tabs and retry are local reading state. */
@Composable
internal fun ToolInspectorSurface(reference: JSONObject, onInspect: suspend (JSONObject) -> JSONObject?, onClose: (() -> Unit)? = null) {
    // A new reference owns a new state object as well as cancelling the previous effect.
    // Even a reader that returns after cancellation cannot publish into the new inspector.
    val identity = reference.toString()
    key(identity) {
        var attempt by remember { mutableIntStateOf(0) }
        var mode by rememberSaveable { mutableStateOf("details") }
        var result by remember { mutableStateOf(InspectorRead()) }
        val reader by rememberUpdatedState(onInspect)
        LaunchedEffect(attempt) {
            // Keep the last body during retry: collapsing a tall lazy row can dispose
            // its inspector before the replacement read returns.
            result = result.copy(loading = true, failed = false)
            result = try {
                val response = reader(JSONObject(identity))
                currentCoroutineContext().ensureActive()
                InspectorRead(loading = false, view = response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                InspectorRead(loading = false, failed = true)
            }
        }
        ToolInspectorContent(result.view, result.loading, result.failed, mode, { mode = it }, { attempt++ }, onClose)
    }
}

@Composable
internal fun ToolInspectorContent(
    view: JSONObject?, loading: Boolean, failed: Boolean, mode: String,
    onMode: (String) -> Unit, onRetry: () -> Unit, onClose: (() -> Unit)? = null,
) {
    val colors = Ot3Theme.colors
    val available = view?.optBoolean("available") == true
    val failure = view?.optBoolean("failed") == true
    Column(Modifier.fillMaxWidth().testTag("thread_inspector"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(view?.optString("title").orEmpty().ifBlank { stringResource(R.string.ot3_inspector_activity_details) },
                color = colors.foreground,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("thread_inspector_title"))
            Ot3IconButton("refresh-cw", stringResource(R.string.ot3_dialog_retry), onRetry, enabled = !loading, modifier = Modifier.testTag("thread_inspector_retry"))
            if (onClose != null) Ot3IconButton("x", stringResource(R.string.ot3_dialog_close), onClose, modifier = Modifier.testTag("thread_inspector_close"))
        }
        if (loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ot3Spinner(size = 12.dp)
            Text(stringResource(R.string.ot3_inspector_loading), color = colors.dim, style = MaterialTheme.typography.bodySmall)
        }
        when {
            loading && view == null -> Unit
            failed -> Text(stringResource(R.string.ot3_inspector_unavailable_error), color = colors.failed, style = MaterialTheme.typography.bodySmall)
            view == null -> Text(stringResource(R.string.ot3_inspector_disconnected), color = colors.dim, style = MaterialTheme.typography.bodySmall)
            !available -> Text(stringResource(R.string.ot3_inspector_unavailable), color = colors.dim, style = MaterialTheme.typography.bodySmall)
            else -> {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("details" to stringResource(R.string.ot3_inspector_details),
                        "metadata" to stringResource(R.string.ot3_inspector_metadata),
                        "raw" to stringResource(R.string.ot3_inspector_raw_item)).forEach { (key, label) ->
                        Ot3Button(label, { onMode(key) },
                            modifier = Modifier.testTag("thread_inspector_tab_$key").semantics { selected = mode == key },
                            style = if (mode == key) Ot3ButtonStyle.PRIMARY else Ot3ButtonStyle.SECONDARY)
                    }
                }
                val status = view.optString("status")
                if (status.isNotBlank() || !view.isNull("exitCode")) Text(
                    listOfNotNull(status.takeIf { it.isNotBlank() }, if (view.isNull("exitCode")) null else stringResource(R.string.ot3_inspector_exit_code) + ": " + view.get("exitCode")).joinToString(" · "),
                    color = if (failure) colors.failed else colors.dim, modifier = Modifier.testTag("thread_inspector_status"), style = MaterialTheme.typography.bodySmall)
                if (mode == "details" && view.optBoolean("emptyOutput")) Text(stringResource(R.string.ot3_inspector_empty_output), color = colors.dim, style = MaterialTheme.typography.bodySmall)
                if (view.optBoolean("outputOmitted")) Text(stringResource(R.string.ot3_inspector_output_omitted), color = colors.dim, style = MaterialTheme.typography.bodySmall)
                val sections = when (mode) {
                    "raw" -> listOf(JSONObject().put("key", "raw").put("label", stringResource(R.string.ot3_inspector_raw_item)).put("text", view.optString("raw")).put("truncated", view.optBoolean("rawTruncated")))
                    "metadata" -> listOf(JSONObject().put("key", "metadata").put("label", stringResource(R.string.ot3_inspector_metadata)).put("text", view.optString("metadata")).put("truncated", view.optBoolean("metadataTruncated")))
                    else -> view.optJSONArray("sections").objects()
                }
                sections.forEachIndexed { index, section ->
                    key(mode, index, section.optString("key")) { InspectorSection(section) }
                }
            }
        }
    }
}

@Composable
private fun InspectorSection(section: JSONObject) {
    val sectionKey = section.optString("key")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(section.optString("label"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            CopyText(section.optString("text"), "inspector_$sectionKey")
        }
        SelectionContainer {
            Text(section.optString("text"), fontFamily = FiraCode, style = MaterialTheme.typography.bodySmall,
                color = if (sectionKey == "input") Ot3Theme.colors.foreground else Ot3Theme.colors.dim,
                modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 320.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Ot3Theme.colors.foreground.copy(alpha = 0.025f))
                    .border(1.dp, Ot3Theme.colors.foreground.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                    .verticalScroll(rememberScrollState()).padding(8.dp)
                    .testTag("thread_inspector_section_$sectionKey"))
        }
        if (section.optBoolean("truncated")) Text(stringResource(R.string.ot3_inspector_truncated),
            style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.failed)
    }
}
