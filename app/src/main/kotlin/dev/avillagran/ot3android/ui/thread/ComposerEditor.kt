package dev.avillagran.ot3android.ui.thread

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.ot3android.ui.kit.Ot3Spinner
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/** Only UI selection/focus is saved. Text remains owned by the existing thread draft. */
internal class ComposerEditorState(text: String = "", private var restored: TextRange? = null, var focusWanted: Boolean = false) {
    var value by mutableStateOf(TextFieldValue(text, TextRange(text.length)))
        private set
    private var pendingToken: String? = null
    private var restoreFocus = focusWanted
    var requestFocus = false
    var generation = 0
        private set
    fun newSurface() { generation++; value = value.copy(composition = null) }
    fun synchronize(text: String, token: String? = null) {
        if (pendingToken != null && token != null && token != pendingToken) return
        pendingToken = null // A matching echo or an authoritative external change.
        if (text == value.text) return
        val range = restored ?: value.selection
        value = TextFieldValue(text, TextRange(range.start.coerceIn(0, text.length), range.end.coerceIn(0, text.length)))
        if (text.isNotEmpty()) restored = null
    }
    fun edit(next: TextFieldValue, surface: Int = generation): String? {
        if (surface != generation) return null
        val changed = next.text != value.text
        // A restored field mounts before its Room/VM draft arrives. Its empty
        // focus/selection callbacks must not consume the saved nonempty range.
        if (!changed && restored != null && value.text.isEmpty()) return null
        restored = null; value = next
        return if (changed) java.util.UUID.randomUUID().toString().also { pendingToken = it } else null
    }
    fun finishComposition(surface: Int = generation) { if (surface == generation) value = value.copy(composition = null) }
    fun submitted() { pendingToken = null }
    fun releaseFocus() { requestFocus = false; restoreFocus = false; focusWanted = false }
    fun takeFocusRequest(): Boolean = (requestFocus || restoreFocus).also { requestFocus = false; restoreFocus = false }
    private fun saved(): List<Any> { val range = restored ?: value.selection; return listOf(range.start, range.end, focusWanted) }
    companion object {
        val StateSaver = Saver<ComposerEditorState, List<Any>>(save = { it.saved() }, restore = { restored(it) })
        fun restored(saved: List<Any>) = ComposerEditorState(restored = TextRange(saved[0] as Int, saved[1] as Int), focusWanted = saved[2] as Boolean)
    }
}

/** Normal selection survives a temporary queued edit; at most those two UI editors are retained. */
internal class ComposerEditors {
    private var normal: ComposerEditorState? = null
    private var queued: ComposerEditorState? = null
    private var queuedId: String? = null
    private var selected: ComposerEditorState? = null
    fun editor(run: String?, text: String): ComposerEditorState {
        val result = if (run == null) normal ?: ComposerEditorState(text).also { normal = it }
        else {
            if (queuedId != run) { queuedId = run; queued = ComposerEditorState(text) }
            checkNotNull(queued)
        }
        if (selected !== result) { selected?.newSurface(); selected?.releaseFocus(); selected = result }
        return result
    }

    companion object {
        val StateSaver = Saver<ComposerEditors, List<Any>>(
            save = { memory -> with(ComposerEditorState.StateSaver) { listOf(memory.normal?.let { save(it) }.orEmpty(), memory.queuedId.orEmpty(), memory.queued?.let { save(it) }.orEmpty()) } },
            restore = { saved -> ComposerEditors().apply {
                @Suppress("UNCHECKED_CAST") val normalSaved = saved[0] as List<Any>
                @Suppress("UNCHECKED_CAST") val queueSaved = saved[2] as List<Any>
                if (normalSaved.isNotEmpty()) normal = ComposerEditorState.restored(normalSaved)
                queuedId = (saved[1] as String).takeIf { it.isNotEmpty() }
                if (queueSaved.isNotEmpty()) queued = ComposerEditorState.restored(queueSaved)
            } },
        )
    }
}

internal enum class ComposerKeyAction { Platform, Consume, Newline, Send, Close }
/** A keyboard source alone does not prove hardware: IMEs also use SOURCE_KEYBOARD. */
internal fun composerKeyAction(key: Int, down: Boolean, repeat: Int, soft: Boolean, virtual: Boolean, keyboard: Boolean,
    shift: Boolean, ctrl: Boolean, alt: Boolean, meta: Boolean, composing: Boolean, expanded: Boolean,
): ComposerKeyAction {
    if (composing) return ComposerKeyAction.Platform
    val hardware = keyboard && !soft && !virtual
    if (hardware && key == AndroidKeyEvent.KEYCODE_ESCAPE && expanded) return if (down && repeat == 0) ComposerKeyAction.Close else ComposerKeyAction.Consume
    if (key != AndroidKeyEvent.KEYCODE_ENTER && key != AndroidKeyEvent.KEYCODE_NUMPAD_ENTER) return ComposerKeyAction.Platform
    if (!hardware) return ComposerKeyAction.Platform
    if (!down || repeat > 0) return ComposerKeyAction.Consume
    if (alt || meta || (ctrl && shift)) return ComposerKeyAction.Consume
    if (ctrl) return if (expanded) ComposerKeyAction.Send else ComposerKeyAction.Consume // Compact Ctrl is an unsupported alternate-delivery contract.
    return if (expanded || shift) ComposerKeyAction.Newline else ComposerKeyAction.Send
}

@Composable
internal fun ComposerEditor(editor: ComposerEditorState, expanded: Boolean, canSend: Boolean, sending: Boolean,
    onDraftChange: (String, String) -> Unit, onSend: () -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier,
    toolbar: @Composable () -> Unit = {},
) {
    val colors = Ot3Theme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(if (focused) colors.composerFocus else colors.composerBorder, Ot3Theme.motion.colorChange(), label = "composerBorder")
    val requester = remember { FocusRequester() }
    val surface = remember(editor) { editor.generation }
    LaunchedEffect(editor) { if (focused) editor.focusWanted = true; if (editor.takeFocusRequest()) requester.requestFocus() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    fun update(next: TextFieldValue) {
        // Disposal/rotation blur can collapse a range after onSaveInstanceState.
        if (next.text == editor.value.text && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            if (next.composition == null) editor.finishComposition(surface)
            return
        }
        editor.edit(next, surface)?.let { onDraftChange(next.text, it) }
    }
    fun newline() {
        if (sending || editor.value.composition != null) return
        val current = editor.value
        val start = current.selection.min
        update(TextFieldValue(current.text.replaceRange(start, current.selection.max, "\n"), TextRange(start + 1)))
    }
    Surface(color = colors.background, modifier = modifier.fillMaxWidth().testTag("thread_composer_surface")) {
        Column {
            Row(Modifier.then(if (expanded) Modifier.weight(1f) else Modifier).padding(horizontal = 12.dp, vertical = 6.dp)
                .border(1.dp, border, RoundedCornerShape(17.dp)).background(colors.surface, RoundedCornerShape(17.dp)).padding(4.dp), verticalAlignment = Alignment.Bottom) {
                BasicTextField(value = editor.value, onValueChange = ::update, enabled = !sending, interactionSource = interaction,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.foreground), cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default), keyboardActions = KeyboardActions { newline() },
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    modifier = Modifier.weight(1f).then(if (expanded) Modifier.fillMaxHeight() else Modifier.heightIn(min = 56.dp, max = 130.dp))
                        .focusRequester(requester).onFocusChanged { editor.focusWanted = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            val device = native.device
                            when (composerKeyAction(native.keyCode, native.action == AndroidKeyEvent.ACTION_DOWN, native.repeatCount,
                                native.flags and AndroidKeyEvent.FLAG_SOFT_KEYBOARD != 0,
                                native.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD || device == null || device.isVirtual,
                                native.isFromSource(InputDevice.SOURCE_KEYBOARD), native.isShiftPressed, native.isCtrlPressed,
                                native.isAltPressed, native.isMetaPressed, editor.value.composition != null, expanded)) {
                                ComposerKeyAction.Platform -> false
                                ComposerKeyAction.Consume -> true
                                ComposerKeyAction.Newline -> { newline(); true }
                                ComposerKeyAction.Send -> { if (canSend && !sending) onSend(); true }
                                ComposerKeyAction.Close -> { onToggle(); true }
                            }
                        }.testTag(ThreadScreenTags.COMPOSER),
                    decorationBox = { inner -> Box(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                        if (editor.value.text.isEmpty()) Text(stringResource(R.string.thread_composer_hint), style = MaterialTheme.typography.bodyMedium, color = colors.dim)
                        inner()
                    } },
                )
                if (!expanded) {
                    Ot3IconButton("square-pen", stringResource(R.string.ot3_composer_edit), onToggle, modifier = Modifier.testTag("composer_expand"))
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (sending) Ot3Spinner(Modifier.testTag(ThreadScreenTags.SENDING), size = 24.dp)
                        else Ot3IconButton("arrow-up", stringResource(R.string.thread_send), onSend, modifier = Modifier.testTag(ThreadScreenTags.SEND), enabled = canSend, tint = colors.accent)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { toolbar() }
        }
    }
}
