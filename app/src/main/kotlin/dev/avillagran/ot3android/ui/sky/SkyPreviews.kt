package dev.avillagran.ot3android.ui.sky

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Debug previews of the header over the two reference themes (UI_SPEC 5: "sky in a dark
// and a light theme"). Colours are tokyo-night's and catppuccin-latte's from tokens.md.

internal val PreviewTokyoNight = SkyPalette.derive(
    background = Color(0xFF1A1B26),
    foreground = Color(0xFFA9B1D6),
    accent = Color(0xFF7AA2F7),
)
internal val PreviewTokyoNightDim = Color(0xFF6C708A)

internal val PreviewCatppuccinLatte = SkyPalette.derive(
    background = Color(0xFFEFF1F5),
    foreground = Color(0xFF4C4F69),
    accent = Color(0xFF1E66F5),
)
internal val PreviewCatppuccinLatteDim = Color(0xFF9295A5)

@Composable
private fun SkyHeaderSample(palette: SkyPalette, dim: Color) {
    Box(Modifier.background(palette.background)) {
        SkyHeader(palette = palette, motion = SkyMotion.Full, energy = 1f, modifier = Modifier.fillMaxWidth()) {
            Column {
                SkyBrandRow(foreground = palette.foreground, dim = dim) {
                    Box(Modifier.size(6.dp).background(palette.accent, CircleShape))
                }
                Box(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp).height(48.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("Search threads…", color = dim, fontSize = 14.sp)
                }
            }
        }
    }
}

@Preview(name = "Sky, tokyo-night", widthDp = 411, heightDp = 200)
@Composable
private fun SkyHeaderTokyoNightPreview() = SkyHeaderSample(PreviewTokyoNight, PreviewTokyoNightDim)

@Preview(name = "Sky, catppuccin-latte", widthDp = 411, heightDp = 200)
@Composable
private fun SkyHeaderCatppuccinLattePreview() = SkyHeaderSample(PreviewCatppuccinLatte, PreviewCatppuccinLatteDim)
