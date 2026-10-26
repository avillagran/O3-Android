package dev.avillagran.ot3android.ui.thread

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36])
class MessageImagesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val row = JSONObject("""{"id":"prompt","role":"user","text":"Revisa la captura","attachments":[{"id":"shot","name":"Captura.png","type":"image","mimeType":"image/png"}]}""")

    @Test fun extremeDimensionsStayInsidePixelBudgetAndInvalidDataFails() {
        for ((w, h) in listOf(100000 to 1, 1 to 100000, Int.MAX_VALUE to Int.MAX_VALUE, 8192 to 4096)) {
            val sample = readerImageSample(w, h, 640)
            assertTrue((w.toLong() + sample - 1) / sample <= 640)
            assertTrue((h.toLong() + sample - 1) / sample <= 640)
        }
        val original = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        original.recycle()
        val preview = decodeReaderImage(bytes, 640)
        assertEquals(512, preview.width)
        assertEquals(256, preview.height)
        preview.recycle()
        assertThrows(IllegalArgumentException::class.java) { decodeReaderImage(byteArrayOf(1, 2, 3), 640) }
    }

    @Test fun failedReadRetriesAndFullscreenUsesSeparateBoundedRead() {
        var reads = 0
        val image = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        rule.setContent { Ot3Theme { CompositionLocalProvider(LocalReaderImages provides ReaderImages("first") {
            reads++
            if (reads == 1) error("image_unavailable") else bytes
        }) { TimelineReader(JSONObject().put("timeline", org.json.JSONArray().put(row)), emptyList()) } } }
        rule.onNodeWithText("Retry").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithContentDescription("Captura.png").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("thread_image_shot_640").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("thread_image_shot_2048").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("thread_image_shot_2048").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithTag("thread_image_shot_2048").assertDoesNotExist()
        assertTrue(reads >= 3)
    }

    @Test fun generationSwitchCancelsOldReadAndDropsOldDialog() {
        var generation by mutableStateOf("old")
        val cancelled = CompletableDeferred<Unit>()
        var reads = 0
        rule.setContent { Ot3Theme { CompositionLocalProvider(LocalReaderImages provides ReaderImages(generation) {
            reads++
            if (generation == "old") try { awaitCancellation() } finally { cancelled.complete(Unit) }
            null
        }) { TimelineReader(JSONObject().put("timeline", org.json.JSONArray().put(row)), emptyList()) } } }
        rule.waitUntil(5000) { reads == 1 }
        rule.onNodeWithTag("thread_image_shot_640").performClick()
        rule.onNodeWithText("Close").assertIsDisplayed()
        rule.runOnIdle { generation = "new" }
        rule.waitForIdle()
        rule.waitUntil(5000) { cancelled.isCompleted }
        rule.onNodeWithText("Close").assertDoesNotExist()
        rule.onNodeWithText("Retry").assertIsDisplayed()
    }
}
