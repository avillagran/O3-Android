package dev.avillagran.ot3android.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.ui.theme.Ot3Radii
import dev.avillagran.ot3android.ui.theme.Ot3Sizes
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/** A sheet's stops (UI_SPEC 2.2). */
enum class Ot3SheetStops {
    /** As tall as its content, up to [TALL_FRACTION] of the window. */
    CONTENT,

    /** Material's partial stop, then [TALL_FRACTION] of the window. */
    PARTIAL_AND_TALL,

    /** [TALL_FRACTION] of the window only. */
    TALL;

    companion object {
        const val TALL_FRACTION = 0.92f
    }
}

/**
 * A bottom sheet styled as UI_SPEC 2.2: `background`, top radius 16, a 1 dp `cardBorder`
 * hairline along the top edge, ot3's 32 × 4 dp drag handle in fg @ 0.15 and a black @ 0.5
 * scrim. The partial stop of [Ot3SheetStops.PARTIAL_AND_TALL] is Material's (half the
 * window): Material 3 1.4 has no custom detents (docs/DECISIONS.md, 2026-10-25).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Ot3Sheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    stops: Ot3SheetStops = Ot3SheetStops.CONTENT,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Ot3Theme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = stops != Ot3SheetStops.PARTIAL_AND_TALL)
    val shape = RoundedCornerShape(topStart = Ot3Radii.sheet, topEnd = Ot3Radii.sheet)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.sheetTopEdge(),
        sheetState = sheetState,
        shape = shape,
        containerColor = colors.background,
        contentColor = colors.foreground,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        dragHandle = { Ot3SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (stops == Ot3SheetStops.CONTENT) Modifier else Modifier.fillMaxHeight(Ot3SheetStops.TALL_FRACTION))
                .navigationBarsPadding(),
        ) {
            if (title != null) Ot3SheetTitle(title)
            content()
            Box(Modifier.padding(bottom = Ot3Spacing.lg))
        }
    }
}

/** ot3's drag handle: 32 × 4 dp, fg @ 0.15, with 10 dp above and 6 dp below. */
@Composable
fun Ot3SheetHandle() {
    Box(
        Modifier
            .padding(top = Ot3Spacing.xl, bottom = Ot3Spacing.md)
            .size(width = 32.dp, height = 4.dp)
            .background(Ot3Theme.colors.foreground.copy(alpha = HANDLE_ALPHA), RoundedCornerShape(2.dp)),
    )
}

/** A sheet title: titleSmall (16 sp Medium) with the phone gutter. */
@Composable
fun Ot3SheetTitle(title: String) {
    Text(
        title,
        style = Ot3Theme.typography.material.titleSmall,
        color = Ot3Theme.colors.foreground,
        modifier = Modifier.padding(horizontal = Ot3Spacing.phoneGutter, vertical = Ot3Spacing.lg),
    )
}

/**
 * A sheet list item: 48 dp, optional leading icon, the label in body text, and when
 * [selected] the accent @ 0.20 fill and a check, as ot3's select popup highlights
 * (`ot3:ui/qml/T3Select.qml:52-58`). [destructive] draws it in `failed`.
 */
@Composable
fun Ot3SheetItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconName: String? = null,
    selected: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    detail: String? = null,
) {
    val colors = Ot3Theme.colors
    val tone = if (destructive) colors.failed else colors.foreground
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Ot3Spacing.lg)
            .heightIn(min = Ot3Sizes.touchTarget)
            .background(if (selected) colors.accent.copy(alpha = SELECTED_ALPHA) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = Ot3Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.xxl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconName != null) Ot3Icon(iconName, null, tint = if (destructive) colors.failed else colors.dim, size = Ot3Sizes.icon)
        Column(Modifier.weight(1f)) {
            Text(label, color = tone, style = Ot3Theme.typography.material.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, color = colors.dim, style = Ot3Theme.typography.material.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) Ot3Icon("check", null, tint = colors.accent)
    }
}

/** The 1 dp `cardBorder` hairline along the sheet's rounded top edge. */
@Composable
private fun Modifier.sheetTopEdge(): Modifier {
    val color = Ot3Theme.colors.cardBorder
    return drawWithContent {
        drawContent()
        val stroke = Ot3Sizes.border.toPx()
        val r = Ot3Radii.sheet.toPx()
        val inset = stroke / 2
        val path = Path().apply {
            moveTo(inset, r)
            arcTo(Rect(Offset(inset, inset), r * 2 - inset), 180f, 90f, false)
            lineTo(size.width - r, inset)
            arcTo(Rect(Offset(size.width - inset - (r * 2 - inset), inset), r * 2 - inset), 270f, 90f, false)
        }
        drawPath(path, color, style = Stroke(stroke))
    }
}

private fun Rect(topLeft: Offset, side: Float) = Rect(topLeft, androidx.compose.ui.geometry.Size(side, side))

private const val HANDLE_ALPHA = 0.15f
private const val SELECTED_ALPHA = 0.20f
