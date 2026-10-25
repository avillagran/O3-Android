package dev.avillagran.ot3android.ui.sky

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ot3's brand row on the sky (`ot3:ui/qml/T3Panel.qml:1131-1160`; UI_SPEC 3.2, 4.1.1):
 * 62 dp minimum, 18 × 11 dp padding; the T3 logo (32 × 20 dp) and "Code" 21 sp bold,
 * "OMARCHY" 9 sp with 2.3 sp tracking in [dim] under them; [trailing] holds the count
 * pills and the connection dot. ot3 does not translate the wordmark, so neither does this.
 *
 * @param logo the 32 × 20 dp logo; the icon set provides `t3-logo`.
 */
@Composable
fun SkyBrandRow(
    foreground: Color,
    dim: Color,
    modifier: Modifier = Modifier,
    logo: @Composable () -> Unit = { SkyLogoPlaceholder(foreground) },
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = SkyDefaults.BrandHeight)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // The wordmark never mirrors (T3Panel.qml:1143).
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 32.dp, height = 20.dp), contentAlignment = Alignment.Center) { logo() }
                    Text(text = WORDMARK, style = WordmarkStyle, color = foreground)
                }
            }
            Text(text = BRAND, style = BrandStyle, color = dim)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** Stand-in for `t3-logo` until the icon set lands. */
@Composable
fun SkyLogoPlaceholder(tint: Color) {
    Text(text = "T3", style = LogoPlaceholderStyle, color = tint)
}

private const val WORDMARK = "Code"
private const val BRAND = "OMARCHY"

private val WordmarkStyle = TextStyle(fontSize = 21.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
private val BrandStyle = TextStyle(fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 2.3.sp)
private val LogoPlaceholderStyle = TextStyle(fontSize = 19.sp, lineHeight = 20.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
