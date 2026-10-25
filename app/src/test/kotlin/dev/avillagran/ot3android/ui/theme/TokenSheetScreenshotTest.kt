package dev.avillagran.ot3android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.testing.ScreenshotTest
import org.junit.Test

/** Every colour role and the type scale, per theme: the base sheet for docs/ui/compare/ui-1/. */
class TokenSheetScreenshotTest : ScreenshotTest() {
    @Test
    fun tokenSheet() = snapshot("token-sheet") { TokenSheet() }
}

@Composable
private fun TokenSheet() {
    val c = Ot3Theme.colors
    val roles = listOf(
        "background" to c.background, "foreground" to c.foreground, "accent" to c.accent, "muted" to c.muted,
        "dim" to c.dim, "divider" to c.divider, "surface" to c.surface, "card" to c.card,
        "cardBorder" to c.cardBorder, "changesCard" to c.changesCard, "rowSelected" to c.rowSelected,
        "rowPressed" to c.rowPressed, "rowDraft" to c.rowDraft, "field" to c.field, "fieldBorder" to c.fieldBorder,
        "switchOff" to c.switchOff, "inlineCode" to c.inlineCode, "composerBorder" to c.composerBorder,
        "composerFocus" to c.composerFocus, "accentContainer" to c.accentContainer,
        "failedContainer" to c.failedContainer, "onAccent" to c.onAccent, "working" to c.working,
        "done" to c.done, "waiting" to c.waiting, "failed" to c.failed, "warning" to c.warning, "input" to c.input,
    )
    Column(Modifier.padding(horizontal = Ot3Spacing.xxxl, vertical = Ot3Spacing.huge)) {
        Text("${c.themeId} · ${if (c.isLight) "light" else "dark"}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Ot3Spacing.lg))
        roles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = Ot3Spacing.xs)) {
                row.forEach { (name, color) -> Swatch(name, color, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(Ot3Spacing.md))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.sm)) {
            Text("projectPalette", style = MaterialTheme.typography.labelSmall, color = c.dim)
            c.projectPalette.forEach { color ->
                Box(Modifier.size(Ot3Sizes.projectChip).background(color.copy(alpha = 0.14f), RoundedCornerShape(Ot3Radii.projectChip)).border(Ot3Sizes.border, color, RoundedCornerShape(Ot3Radii.projectChip)))
            }
        }
        Spacer(Modifier.height(Ot3Spacing.huge))
        TypeScale()
    }
}

@Composable
private fun Swatch(name: String, color: Color, modifier: Modifier) {
    val c = Ot3Theme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).background(color, RoundedCornerShape(Ot3Radii.row)).border(Ot3Sizes.border, c.cardBorder, RoundedCornerShape(Ot3Radii.row)))
        Spacer(Modifier.width(Ot3Spacing.md))
        Column {
            Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("#%06x".format(color.toArgb() and 0xFFFFFF), style = Ot3Theme.typography.markdownCode, color = c.dim, maxLines = 1)
        }
    }
}

@Composable
private fun TypeScale() {
    val m = MaterialTheme.typography
    val t = Ot3Theme.typography
    val c = Ot3Theme.colors
    val styles: List<Pair<String, TextStyle>> = listOf(
        "headlineMedium 33" to m.headlineMedium, "headlineSmall 28" to m.headlineSmall,
        "markdownH1 23" to t.markdownH1, "markdownH2 21" to t.markdownH2, "titleMedium 19" to m.titleMedium,
        "titleSmall 16" to m.titleSmall, "bodyLarge 15" to m.bodyLarge, "bodyMedium 14" to m.bodyMedium,
        "labelLarge 14" to m.labelLarge, "labelMedium 13" to m.labelMedium, "bodySmall 12" to m.bodySmall,
        "mono 14 -> != === //" to t.mono, "markdownCode 12 fn main() {}" to t.markdownCode,
    )
    styles.forEach { (label, style) -> Text(label, style = style, maxLines = 1) }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.md)) {
        Text("Code", style = t.wordmark)
        Text("OMARCHY", style = t.wordmarkOmarchy, color = c.dim, modifier = Modifier.padding(bottom = Ot3Spacing.xs))
        Box(Modifier.height(Ot3Sizes.countPillHeight).background(c.working, RoundedCornerShape(Ot3Radii.countPill)).padding(horizontal = Ot3Spacing.xs)) {
            Text("12", style = t.countPill, color = c.background)
        }
    }
}
