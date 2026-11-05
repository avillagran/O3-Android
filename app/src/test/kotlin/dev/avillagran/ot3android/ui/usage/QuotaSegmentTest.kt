package dev.avillagran.ot3android.ui.usage

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(sdk = [36])
class QuotaSegmentTest {
    @get:Rule val rule = createComposeRule()
    @Test fun expectedBalanceMarkerExtendsPastBarAndFillWarnsWhenBelowExpected() {
        var foreground = Color.Unspecified
        var urgent = Color.Unspecified
        rule.setContent { Ot3Theme {
            foreground = Ot3Theme.colors.foreground
            urgent = Ot3Theme.colors.failed
            QuotaSegment(JSONObject("""{"availablePercent":30,"expectedAvailable":60}"""), Modifier.width(100.dp).testTag("bar"))
        } }
        val pixels = pixels()
        assertEquals(foreground.toArgb(), pixels.getPixel((pixels.width * .6f).toInt(), pixels.height / 10))
        assertEquals(urgent.toArgb(), pixels.getPixel((pixels.width * .1f).toInt(), pixels.height / 2))
        assertNotEquals(foreground.toArgb(), pixels.getPixel((pixels.width * .4f).toInt(), 0))
    }
    private fun pixels(): android.graphics.Bitmap {
        val file = java.io.File.createTempFile("o3-quota-", ".png", java.io.File("/var/tmp"))
        return try {
            rule.onNodeWithTag("bar").captureRoboImage(file.absolutePath, roborazziOptions = com.github.takahirom.roborazzi.RoborazziOptions(taskType = com.github.takahirom.roborazzi.RoborazziTaskType.Record))
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } finally { file.delete() }
    }
    @Test fun absentExpectedValueDoesNotInventAMarker() {
        var foreground = Color.Unspecified
        rule.setContent { Ot3Theme {
            foreground = Ot3Theme.colors.foreground
            QuotaSegment(JSONObject("""{"availablePercent":30}"""), Modifier.width(100.dp).testTag("bar"))
        } }
        val pixels = pixels()
        for (x in 0 until pixels.width) assertNotEquals(foreground.toArgb(), pixels.getPixel(x, 0))
    }
}
