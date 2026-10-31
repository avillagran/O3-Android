package dev.avillagran.ot3android.ui.thread

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import dev.avillagran.ot3android.testing.ScreenshotTest
import org.json.JSONArray
import org.junit.Test
import java.io.ByteArrayOutputStream

class MarkdownImageScreenshotTest : ScreenshotTest() {
    @Test fun hostImageKeepsAspectAndSurroundingText() {
        val bitmap = Bitmap.createBitmap(600, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(50, 120, 170)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val blocks = JSONArray("""[
          {"type":"paragraph","spans":[{"type":"text","text":"Before host image"}]},
          {"type":"image","id":"markdown-host","url":"/home/host/shot.png","alt":"Host screenshot"},
          {"type":"paragraph","spans":[{"type":"text","text":"After host image"}]}
        ]""")
        snapshot("host-image", beforeCapture = {
            composeRule.waitUntil(5000) { composeRule.onAllNodesWithContentDescription("Host screenshot").fetchSemanticsNodes().isNotEmpty() }
        }) {
            CompositionLocalProvider(LocalReaderImages provides ReaderImages("fixture") { bytes }) {
                Column { MarkdownBlocks(blocks) }
            }
        }
    }
}
