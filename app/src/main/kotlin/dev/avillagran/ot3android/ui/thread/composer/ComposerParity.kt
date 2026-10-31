package dev.avillagran.ot3android.ui.thread.composer

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.composer.*
import dev.avillagran.ot3android.ui.thread.ComposerEditor
import dev.avillagran.ot3android.ui.thread.ComposerEditors

/** Advertise only actions for which the owner supplies a real implementation. */
enum class ComposerAction { Attach, Paste, Undo, Redo, History, ContinueList, Stash, ProjectDrafts, QuickReplies, PlanBuild,
    ImplementPlan, PlanFeedback, OppositeOnce, Background, SendAndNew }

/** Every asynchronous UI intent captures the exact editor scope and revision. */
sealed interface ComposerRequest {
    val expected: ComposerSnapshot
    data class Action(override val expected: ComposerSnapshot, val action: ComposerAction) : ComposerRequest
    data class Send(override val expected: ComposerSnapshot, val delivery: ComposerDelivery) : ComposerRequest
    data class ImportUri(override val expected: ComposerSnapshot, val uri: String) : ComposerRequest
    data class PasteText(override val expected: ComposerSnapshot, val text: String) : ComposerRequest
    data class RemoveAttachment(override val expected: ComposerSnapshot, val id: String) : ComposerRequest
    data class Reference(override val expected: ComposerSnapshot, val reference: ComposerReference) : ComposerRequest
    data class Complete(override val expected: ComposerSnapshot, val id: String) : ComposerRequest
    data class Restore(override val expected: ComposerSnapshot, val id: String, val revision: String) : ComposerRequest
    data class Delete(override val expected: ComposerSnapshot, val id: String, val revision: String) : ComposerRequest
}

/**
 * P20 entrypoint. Text/attachments/durability remain owned by the caller. No ThreadScreen,
 * VM or graph dependency. [capabilities] must omit unbound native/host operations.
 * The owner must revalidate each request against its current session, not just trust UI gates.
 */
@Composable
fun ComposerParity(
    snapshot: ComposerSnapshot,
    capabilities: Set<ComposerAction>,
    onEdit: (ComposerSnapshot, String, ComposerSelection, Boolean) -> Unit,
    onRequest: (ComposerRequest) -> Unit,
    modifier: Modifier = Modifier,
    references: List<ComposerReference> = emptyList(),
    completions: List<ComposerCompletion> = emptyList(),
    error: String? = null,
) {
    var expanded by rememberSaveable(snapshot.scope) { mutableStateOf(false) }
    val editors = rememberSaveable(snapshot.scope.copy(queueMessage = null), saver = ComposerEditors.StateSaver) { ComposerEditors() }
    val editor = editors.editor(snapshot.scope.queueMessage, snapshot.text)
    var restoreSelection by remember(editor) { mutableStateOf(editor.awaitingRestore) }
    // Hydrate a restored editor before its input connection is mounted. Mounting an
    // empty value first lets the IME collapse a saved selection during focus restore.
    editor.synchronize(snapshot.text)
    LaunchedEffect(snapshot.scope, snapshot.version, editor) {
        // Disposal of the previous surface can publish a collapsed range after save.
        // Its saved UI range takes precedence over that late blur echo.
        if (restoreSelection) {
            restoreSelection = false
            onEdit(snapshot, editor.value.text, ComposerSelection(editor.value.selection.start, editor.value.selection.end), false)
            return@LaunchedEffect
        }
        // An IME composition belongs to its input connection, never the restored surface.
        if (snapshot.composing && editor.value.composition == null) {
            onEdit(snapshot, editor.value.text, ComposerSelection(editor.value.selection.start, editor.value.selection.end), false)
        }
        if (!snapshot.composing && snapshot.selection.start in 0..snapshot.text.length && snapshot.selection.end in 0..snapshot.text.length) {
            editor.edit(editor.value.copy(selection = TextRange(snapshot.selection.start, snapshot.selection.end)))
        }
    }
    val context = LocalContext.current
    // Capture picker origin. A result is never rebound to the currently selected thread.
    var picking by remember { mutableStateOf<ComposerSnapshot?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val origin = picking
        picking = null
        if (origin != null && origin.scope == snapshot.scope) uris.take(100).forEach {
            onRequest(ComposerRequest.ImportUri(origin, it.toString()))
        }
    }
    val compact = LocalConfiguration.current.screenHeightDp < 420
    var overflow by remember { mutableStateOf(false) }
    fun request(action: ComposerAction) {
        when (action) {
            ComposerAction.Attach -> { picking = snapshot; picker.launch(arrayOf("*/*")) }
            ComposerAction.Paste -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null) for (index in 0 until minOf(clip.itemCount, 100)) {
                    val item = clip.getItemAt(index)
                    if (item.uri != null) onRequest(ComposerRequest.ImportUri(snapshot, item.uri.toString()))
                    else item.text?.toString()?.let { onRequest(ComposerRequest.PasteText(snapshot, it)) }
                }
            }
            ComposerAction.OppositeOnce -> onRequest(ComposerRequest.Send(snapshot, ComposerDelivery.OppositeOnce))
            ComposerAction.Background -> onRequest(ComposerRequest.Send(snapshot, ComposerDelivery.Background))
            ComposerAction.SendAndNew -> onRequest(ComposerRequest.Send(snapshot, ComposerDelivery.SendAndNew))
            else -> onRequest(ComposerRequest.Action(snapshot, action))
        }
    }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun closeEditor() {
        editor.newSurface(); editor.releaseFocus(); focus.clearFocus(); keyboard?.hide(); expanded = false
    }
    val editorActions: @Composable () -> Unit = {
        if (ComposerAction.Attach in capabilities) Ot3IconButton("paperclip", stringResource(R.string.composer_attach),
            { request(ComposerAction.Attach) }, enabled = snapshot.transformable)
        Box {
            Ot3IconButton("ellipsis-vertical", stringResource(R.string.home_more), { overflow = true },
                modifier = Modifier.testTag("composer_actions"))
            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                capabilities.forEach { action ->
                    val sendAction = action in setOf(ComposerAction.OppositeOnce, ComposerAction.Background, ComposerAction.SendAndNew)
                    DropdownMenuItem(text = { Text(actionLabel(action, snapshot.interaction)) },
                        enabled = if (sendAction) snapshot.sendable else snapshot.transformable,
                        onClick = { overflow = false; request(action) })
                }
            }
        }
    }
    val renderEditor: @Composable (Boolean, Modifier) -> Unit = { full, layout ->
        ComposerEditor(editor, full, snapshot.sendable, snapshot.sending,
            onDraftChange = { _, _ -> },
            onSend = { if (snapshot.sendable) onRequest(ComposerRequest.Send(snapshot, ComposerDelivery.Default)) },
            onToggle = { editor.newSurface(); editor.requestFocus = true; expanded = true },
            modifier = layout,
            onEditorValue = { value ->
                onEdit(snapshot, value.text, ComposerSelection(value.selection.start, value.selection.end), value.composition != null)
            },
            compact = compact, enabled = snapshot.editable, actions = editorActions)
    }
    Column(modifier) {
        if (snapshot.sending) Text(stringResource(R.string.approval_sending), Modifier.testTag("composer_busy"))
        if (snapshot.uncertainCommand != null) Text(stringResource(R.string.composer_uncertain))
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            snapshot.attachments.forEach { file ->
                InputChip(selected = false, onClick = {}, label = {
                    Text(when (file.status) {
                        UploadStatus.Uploading -> stringResource(R.string.composer_uploading, file.name)
                        UploadStatus.Failed -> stringResource(R.string.composer_upload_failed, file.name)
                        UploadStatus.Ready -> file.name
                    })
                }, trailingIcon = {
                    TextButton(onClick = { onRequest(ComposerRequest.RemoveAttachment(snapshot, file.id)) }, enabled = snapshot.editable) {
                        Text(stringResource(R.string.composer_remove))
                    }
                })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            references.forEach { reference ->
                SuggestionChip(onClick = { onRequest(ComposerRequest.Reference(snapshot, reference)) },
                    label = { Text(reference.label) }, enabled = snapshot.transformable)
            }
        }
        // Bounded viewport: long completions must not push the editor/IME controls off-screen.
        if (!snapshot.composing && snapshot.editable) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                completions.take(40).forEach { completion ->
                    SuggestionChip(onClick = { onRequest(ComposerRequest.Complete(snapshot, completion.id)) },
                        label = { Text(listOf(completion.label, completion.description).filter { it.isNotBlank() }.joinToString(" · ")) })
                }
            }
        }
        if (!expanded) renderEditor(false, Modifier)
    }
    if (expanded) androidx.compose.ui.window.Dialog(onDismissRequest = ::closeEditor,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Ot3IconButton("chevron-down", stringResource(R.string.ot3_composer_back), ::closeEditor,
                        modifier = Modifier.testTag("composer_dialog_back"))
                    Text(stringResource(R.string.ot3_composer_edit), Modifier.weight(1f))
                    editorActions()
                    if (snapshot.sending) dev.avillagran.ot3android.ui.kit.Ot3Spinner(Modifier.size(48.dp))
                    else Ot3IconButton("arrow-up", stringResource(R.string.thread_send),
                        { onRequest(ComposerRequest.Send(snapshot, ComposerDelivery.Default)) },
                        enabled = snapshot.sendable, modifier = Modifier.testTag("thread_send"))
                }
                renderEditor(true, Modifier.weight(1f).testTag("composer_expanded"))
            }
        }
    }
}

@Composable
private fun actionLabel(action: ComposerAction, interaction: ComposerInteraction): String = stringResource(when (action) {
    ComposerAction.Attach -> R.string.composer_attach
    ComposerAction.Paste -> R.string.composer_paste
    ComposerAction.Undo -> R.string.composer_undo
    ComposerAction.Redo -> R.string.composer_redo
    ComposerAction.History -> R.string.composer_history
    ComposerAction.ContinueList -> R.string.composer_continue_list
    ComposerAction.Stash -> R.string.composer_stash
    ComposerAction.ProjectDrafts -> R.string.composer_project_drafts
    ComposerAction.QuickReplies -> R.string.composer_quick_replies
    ComposerAction.PlanBuild -> if (interaction == ComposerInteraction.Plan) R.string.composer_build else R.string.composer_plan
    ComposerAction.ImplementPlan -> R.string.composer_implement
    ComposerAction.PlanFeedback -> R.string.composer_feedback
    ComposerAction.OppositeOnce -> R.string.composer_opposite
    ComposerAction.Background -> R.string.composer_background
    ComposerAction.SendAndNew -> R.string.composer_send_new
})

/** Project drawer. Refresh and errors remain explicit; restore/delete use server entry revisions. */
@Composable
fun ComposerProjectDrafts(snapshot: ComposerSnapshot, entries: List<ProjectComposerDraft>, busy: Boolean, error: String?,
    onRefresh: () -> Unit, onRequest: (ComposerRequest) -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(stringResource(R.string.composer_project_drafts), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.composer_refresh)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        entries.forEach { entry ->
            Text(entry.preview)
            Text(stringResource(R.string.composer_attachment_count, entry.attachmentCount))
            Row {
                TextButton(enabled = !busy && snapshot.transformable, onClick = { onRequest(ComposerRequest.Restore(snapshot, entry.id, entry.revision)) }) {
                    Text(stringResource(R.string.composer_restore))
                }
                TextButton(enabled = !busy && snapshot.transformable, onClick = { onRequest(ComposerRequest.Delete(snapshot, entry.id, entry.revision)) }) {
                    Text(stringResource(R.string.composer_delete))
                }
            }
        }
    }
}

/** Caller supplies native recallable text / configured quick replies; selection preserves scratch. */
@Composable
fun ComposerTextChoices(snapshot: ComposerSnapshot, choices: List<String>, onChoose: (ComposerSnapshot, String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState())) {
        choices.take(100).forEach { text ->
            SuggestionChip(onClick = { onChoose(snapshot, text) }, label = { Text(text) }, enabled = snapshot.transformable)
        }
    }
}
