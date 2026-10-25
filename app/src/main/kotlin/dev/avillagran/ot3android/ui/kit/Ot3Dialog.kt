package dev.avillagran.ot3android.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import dev.avillagran.ot3android.ui.theme.Ot3Colors
import dev.avillagran.ot3android.ui.theme.Ot3Radii
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/**
 * ot3's confirmation dialog (`ot3:ui/qml/T3ThreadRow.qml:411-427`) as a Material dialog:
 * `background`, radius 10, padding 18, a 1 dp accent border (`failed` when [destructive]),
 * the title in bold body text, [subtitle] in `dim`, [message] (in `failed` when destructive),
 * then [content] and [error], and the buttons at the end: [dismissText] and [confirmText].
 * The window dims to black @ 0.5.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Ot3Dialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    message: String? = null,
    error: String? = null,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(Ot3Colors.Scrim.alpha) }
        Ot3DialogSurface(
            title = title,
            confirmText = confirmText,
            onConfirm = onConfirm,
            dismissText = dismissText,
            onDismiss = onDismissRequest,
            subtitle = subtitle,
            message = message,
            error = error,
            destructive = destructive,
            confirmEnabled = confirmEnabled,
            content = content,
        )
    }
}

/** The dialog's card, without a window: what [Ot3Dialog] shows, also drawn inline by the catalog. */
@Composable
fun Ot3DialogSurface(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    message: String? = null,
    error: String? = null,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = Ot3Theme.colors
    val type = Ot3Theme.typography.material
    val shape = RoundedCornerShape(Ot3Radii.dialog)
    Column(
        modifier
            .widthIn(max = DIALOG_WIDTH)
            .fillMaxWidth()
            .background(colors.background, shape)
            .border(Ot3Sizes.border, if (destructive) colors.failed else colors.accent, shape)
            .padding(Ot3Spacing.huge)
            .semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(Ot3Spacing.xxl),
    ) {
        Text(title, style = type.bodyMedium.copy(fontWeight = FontWeight.Bold), color = colors.foreground)
        if (subtitle != null) Text(subtitle, style = type.bodyMedium, color = colors.dim)
        if (message != null) Text(message, style = type.bodyMedium, color = if (destructive) colors.failed else colors.foreground)
        content?.invoke(this)
        if (error != null) Text(error, style = type.bodyMedium, color = colors.failed)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.lg, androidx.compose.ui.Alignment.End)) {
            Ot3Button(dismissText, onDismiss, style = Ot3ButtonStyle.SECONDARY)
            Ot3Button(
                confirmText,
                onConfirm,
                style = if (destructive) Ot3ButtonStyle.OUTLINE else Ot3ButtonStyle.PRIMARY,
                destructive = destructive,
                enabled = confirmEnabled,
            )
        }
    }
}

/** ot3's dialog is 430 px wide (`ot3:ui/qml/T3ThreadRow.qml:412`). */
private val DIALOG_WIDTH = 430.dp
