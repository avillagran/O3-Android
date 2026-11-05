package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.testing.ScreenshotTest
import org.junit.Test

class WorkingIndicatorScreenshotTest : ScreenshotTest() {
    @Test fun narrowHeaderWithLargeTextKeepsWorkGlyphSquare() = snapshot("narrow-large-text", beforeCapture = {
        composeRule.onNodeWithTag(ThreadScreenTags.WORKING)
            .assertWidthIsEqualTo(20.dp).assertHeightIsEqualTo(20.dp)
    }) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
            Box(Modifier.fillMaxSize()) {
            Row(Modifier.width(130.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NODE", Modifier.weight(1f), maxLines = 1)
                WorkingLabel()
            }
            }
        }
    }
}
