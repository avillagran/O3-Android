package dev.avillagran.ot3android.ui.sky

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Desktop's 62dp header, with a second action line on narrow/large-text phones.
 * [brandConfig] is deliberately independent of persisted appearance preferences (P02).
 * [logo] remains source-compatible with existing callers; O3 now owns its glyph art.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SkyBrandRow(
    foreground: Color,
    dim: Color,
    modifier: Modifier = Modifier,
    logo: @Composable () -> Unit = {},
    brandConfig: O3BrandConfig = O3BrandConfig(),
    accent: Color = dev.avillagran.ot3android.ui.theme.Ot3Theme.colors.accent,
    elapsedMs: Long? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 18.dp, vertical = 11.dp)) {
        val stacked = maxWidth < 364.dp || LocalDensity.current.fontScale > 1.3f
        val brand: @Composable () -> Unit = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                O3BrandMark(foreground, dim, accent, config = brandConfig, elapsedMs = elapsedMs)
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                brand()
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                    content = trailing)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                brand()
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        }
    }
}
