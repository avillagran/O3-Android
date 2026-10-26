package dev.avillagran.ot3android.ui.sky

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** PixelCopy of the real header, not just shader compilation or a uniform-plan assertion. */
class SkyRenderingTest {
    @get:Rule val rule = createComposeRule()
    @Test fun agslDrawsStars() = renders(false)
    @Test fun forcedCanvasDrawsStars() = renders(true)

    private fun renders(canvas: Boolean) {
        rule.setContent {
            Ot3Theme {
                SkyHeader(PreviewTokyoNight, SkyMotion.Off, 0f,
                    Modifier.size(360.dp, 150.dp).testTag("sky_pixels"), forceCanvas = canvas)
            }
        }
        val pixels = rule.onNodeWithTag("sky_pixels").captureToImage().toPixelMap()
        // A uniform fill or a smooth vertical gradient has no horizontal high-frequency points.
        // Stars must produce multiple local horizontal changes in the solid brand region.
        var edges = 0
        for (y in 2 until pixels.height / 3 step 2) {
            for (x in 2 until pixels.width - 2 step 2) {
                val a = pixels[x, y]
                val b = pixels[x + 2, y]
                if (kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) +
                    kotlin.math.abs(a.blue - b.blue) > 0.035f) edges++
            }
        }
        assertTrue("Header must contain stars (canvas=$canvas, edges=$edges)", edges > 12)
    }
}
