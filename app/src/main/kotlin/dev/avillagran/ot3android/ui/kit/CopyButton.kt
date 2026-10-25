package dev.avillagran.ot3android.ui.kit

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** ot3's copy states (`ot3:ui/qml/T3CopyButton.qml:12-18`). */
enum class CopyState(val iconName: String) {
    IDLE("copy"),
    BUSY("loader-circle"),
    COPIED("check"),
    ERROR("circle-alert");

    /** Copied and failed go back to idle after [RESET_MS]. */
    val resets: Boolean get() = this == COPIED || this == ERROR

    companion object {
        const val RESET_MS = 1800L
    }
}

/**
 * ot3's copy button: copies [text] to the clipboard, showing copy → loader → check (accent)
 * or alert (`failed`), and resets after 1.8 s. [label] is the idle tooltip and name ("Copy
 * message", "Copy code"); the others are ot3's "Copied!" and "Copy failed".
 */
@Composable
fun CopyButton(
    text: String,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.ot3_kit_copy_message),
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(CopyState.IDLE) }
    LaunchedEffect(state) {
        if (state.resets) {
            delay(CopyState.RESET_MS)
            state = CopyState.IDLE
        }
    }
    CopyButton(
        state = state,
        enabled = text.isNotEmpty(),
        label = label,
        modifier = modifier,
        onClick = {
            state = CopyState.BUSY
            scope.launch {
                state = runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) }
                    .fold({ CopyState.COPIED }, { CopyState.ERROR })
            }
        },
    )
}

/** The stateless copy button: [state] decides icon, tint and tooltip. */
@Composable
fun CopyButton(
    state: CopyState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = stringResource(R.string.ot3_kit_copy_message),
) {
    val colors = Ot3Theme.colors
    val tooltip = when (state) {
        CopyState.COPIED -> stringResource(R.string.ot3_kit_copied)
        CopyState.ERROR -> stringResource(R.string.ot3_kit_copy_failed)
        else -> label
    }
    val busy = state == CopyState.BUSY
    val clock by rememberKitClock(busy)
    Ot3IconButton(
        iconName = state.iconName,
        contentDescription = tooltip,
        tooltip = tooltip,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !busy,
        tint = when (state) {
            CopyState.COPIED -> colors.accent
            CopyState.ERROR -> colors.failed
            else -> colors.foreground
        },
        iconRotation = if (busy) (clock * LOADER_DEGREES_PER_SECOND) % 360f else 0f,
    )
}

private const val LOADER_DEGREES_PER_SECOND = 360f
