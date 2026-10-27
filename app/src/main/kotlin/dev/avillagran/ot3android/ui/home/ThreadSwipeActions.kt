package dev.avillagran.ot3android.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.core.home.HomeRow
import dev.avillagran.ot3android.core.home.SwipeAction
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlin.math.max

/** Home reveals actions; the live shell remains the authority for moving/removing rows. */
@Composable
internal fun ThreadSwipeActions(row: HomeRow, onAction: (SwipeAction) -> Unit, modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val primary = row.swipe.primary
    if (!row.swipe.enabled || primary == null) { content(modifier); return }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val actionWidth = with(LocalDensity.current) { 88.dp.toPx() }
    val revealWidth = actionWidth * if (row.swipe.secondary != null) 2 else 1
    var width by remember { mutableIntStateOf(0) }
    var offset by remember(row.key) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val animates = Ot3Theme.motion.animates
    val shown by animateFloatAsState(offset, if (dragging || !animates) snap() else spring(dampingRatio = .855f, stiffness = 471f), label = "thread swipe")
    Box(modifier.clipToBounds().onSizeChanged { width = it.width }.pointerInput(row.key, row.swipe, direction, width) {
        detectHorizontalDragGestures(onDragStart = { dragging = true }, onDragCancel = { dragging = false; offset = 0f },
            onHorizontalDrag = { change, amount ->
                change.consume()
                offset = (offset + amount * direction).coerceIn(-width.toFloat(), 0f)
            }, onDragEnd = {
                dragging = false
                if (-offset >= max(revealWidth + actionWidth / 2, width * .58f)) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    offset = 0f
                    onAction(primary)
                } else offset = if (-offset > revealWidth * .35f) -revealWidth else 0f
            })
    }) {
        if (shown < -1f) Row(Modifier.matchParentSize().background(Ot3Theme.colors.surface),
            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            listOfNotNull(row.swipe.primary, row.swipe.secondary).forEach { action ->
                TextButton(onClick = { offset = 0f; onAction(action) },
                    modifier = Modifier.width(88.dp).fillMaxHeight().testTag("thread_swipe_${row.key}_${action.name}")) {
                    Text(swipeText(action))
                }
            }
        }
        content(Modifier.fillMaxWidth().graphicsLayer { translationX = shown * direction })
    }
}
