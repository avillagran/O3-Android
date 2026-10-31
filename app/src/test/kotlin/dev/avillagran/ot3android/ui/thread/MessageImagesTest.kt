package dev.avillagran.ot3android.ui.thread

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
    // La regla v2 encola las continuaciones: el decoder no debe reanudar el
    // recompositor sin confinarlo al hilo de UI al volver de Dispatchers.Default.
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val row = JSONObject("""{"id":"prompt","role":"user","text":"Revisa la captura","attachments":[{"id":"shot","name":"Captura.png","type":"image","mimeType":"image/png"}]}""")

    @Test fun twoHundredMarkdownImagesReadOnlyNearViewportAndReleasePixelsOnScroll() {
        val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        val reads = mutableSetOf<String>()
        val blocks = org.json.JSONArray().apply {
            repeat(200) { put(JSONObject().put("type", "image").put("id", "image-$it").put("url", "/image-$it.png").put("alt", "Image $it")) }
        }
        rule.setContent { Ot3Theme {
            CompositionLocalProvider(LocalReaderImages provides ReaderImages("viewport") { descriptor ->
                reads.add(descriptor.getString("id")); bytes
            }) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MarkdownBlocks(blocks)
                    Text("End", Modifier.testTag("image-end"))
                }
            }
        } }
        rule.waitUntil(5000) { reads.isNotEmpty() }
        rule.waitForIdle()
        assertTrue("Only nearby images should read: ${reads.size}", reads.size < 30)
        assertTrue(ReaderBitmapBudget.shared.used <= ReaderBitmapBudget.shared.limit)
        rule.onNodeWithTag("image-end").performScrollTo()
        rule.waitUntil(5000) { "image-199" in reads }
        // Reading starts before Dispatchers.Default decodes and publishes the bitmap.
        // Compose idle alone does not await that external work or the resulting layout.
        rule.waitUntil(5000) { rule.onNodeWithContentDescription("Image 199").isDisplayed() }
        rule.waitForIdle()
        assertTrue("Scrolling should not read intermediate images: ${reads.size}", reads.size < 60)
        rule.onNodeWithContentDescription("Image 0").assertDoesNotExist()
        rule.onNodeWithContentDescription("Image 199").assertIsDisplayed()
        assertTrue(ReaderBitmapBudget.shared.used <= ReaderBitmapBudget.shared.limit)
    }

    @Test fun playbackRetryOwnsANewFileAndIgnoresOldCallbacks() {
        var reads = 0
        val media = JSONObject().put("id", "clip-retry").put("name", "Retry.mp4").put("type", "file").put("mimeType", "video/mp4")
        rule.setContent { Ot3Theme {
            CompositionLocalProvider(LocalReaderImages provides ReaderImages("playback-retry") { reads++; byteArrayOf(1, 2, 3) }) {
                ReaderImageDialog(media) {}
            }
        } }
        fun videoViews(): List<android.widget.VideoView> {
            fun walk(view: android.view.View): List<android.widget.VideoView> = when (view) {
                is android.widget.VideoView -> listOf(view)
                is android.view.ViewGroup -> (0 until view.childCount).flatMap { walk(view.getChildAt(it)) }
                else -> emptyList()
            }
            return org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView?.let { walk(it) } ?: emptyList()
        }
        rule.waitUntil(5000) { videoViews().isNotEmpty() }
        val old = videoViews().single()
        val shadow = org.robolectric.Shadows.shadowOf(old)
        val oldFile = java.io.File(android.net.Uri.parse(shadow.videoURIString).path!!)
        val staleError = shadow.onErrorListener
        val stalePrepared = shadow.onPreparedListener
        assertTrue(oldFile.exists())
        rule.runOnIdle { staleError.onError(null, 1, 0) }
        rule.onNodeWithText("Retry").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        rule.waitForIdle()
        rule.waitUntil(5000) { reads == 2 }
        rule.waitUntil(5000) { videoViews().any { it !== old } }
        val next = videoViews().single()
        val nextFile = java.io.File(android.net.Uri.parse(org.robolectric.Shadows.shadowOf(next).videoURIString).path!!)
        assertNotEquals(oldFile, nextFile)
        assertFalse(oldFile.exists())
        assertTrue(nextFile.exists())
        rule.runOnIdle { staleError.onError(null, 1, 0); stalePrepared.onPrepared(null) }
        rule.onNodeWithText("Retry").assertDoesNotExist()
        rule.runOnIdle { org.robolectric.Shadows.shadowOf(next).onPreparedListener.onPrepared(null) }
        assertTrue(next.isPlaying)
    }

    @Test fun markdownImagesInListsTablesAndTextReuseTheReaderAndViewer() {
        val bitmap = Bitmap.createBitmap(100, 20, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val destinations = mutableSetOf<String>()
        val blocks = org.json.JSONArray("""[
          {"type":"list","items":[{"blocks":[{"type":"image","id":"list","url":"assets/list.png","alt":"List image"}]}]},
          {"type":"table","header":[[{"type":"text","text":"Photo"}]],"rows":[[[{"type":"image","id":"table","url":"/host/table.png","alt":"Table image"}]]]},
          {"type":"paragraph","spans":[{"type":"text","text":"Before inline"},{"type":"image","id":"inline","url":"https://example.org/image.png","alt":"Inline image"},{"type":"text","text":"After inline"}]}
        ]""")
        rule.setContent { Ot3Theme { CompositionLocalProvider(LocalReaderImages provides ReaderImages("markdown") { descriptor ->
            destinations.add(descriptor.getString("source"))
            bytes
        }) { TimelineReader(JSONObject().put("timeline", org.json.JSONArray().put(JSONObject()
            .put("id", "markdown").put("role", "assistant").put("blocks", blocks))), emptyList()) } } }
        rule.waitUntil(5000) { destinations.size == 3 }
        rule.onNodeWithContentDescription("List image").assertIsDisplayed()
        rule.onNodeWithContentDescription("Table image").assertIsDisplayed()
        rule.onNodeWithText("Before inline").assertIsDisplayed()
        rule.onNodeWithText("After inline").assertIsDisplayed()
        rule.onNodeWithTag("thread_image_inline_640").performClick()
        rule.onNodeWithTag("thread_image_inline_2048").assertIsDisplayed()
        assertEquals(setOf("assets/list.png", "/host/table.png", "https://example.org/image.png"), destinations)
    }

    @Test fun mediaAttachmentUsesTheSharedDialogAndRetriesFailedReads() {
        var reads = 0
        val mediaRow = JSONObject("""{"id":"media","role":"assistant","attachments":[{"id":"clip","name":"Clip.mp4","type":"file","mimeType":"video/mp4"}]}""")
        rule.setContent { Ot3Theme { CompositionLocalProvider(LocalReaderImages provides ReaderImages("media") {
            reads++
            error("asset_unavailable")
        }) { TimelineReader(JSONObject().put("timeline", org.json.JSONArray().put(mediaRow)), emptyList()) } } }
        rule.onNodeWithText("Clip.mp4").performClick()
        rule.onNodeWithText("Retry").performClick()
        rule.waitUntil(5000) { reads == 2 }
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun growingInlineWidthRedecodesCachedBytesWithoutAnotherRead() {
        val image = Bitmap.createBitmap(962, 622, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        var wide by mutableStateOf(false)
        var reads = 0
        val descriptor = JSONObject().put("id", "resize").put("name", "Resize").put("source", "/resize.png")
        rule.setContent { Ot3Theme {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(3.4f),
                LocalReaderImages provides ReaderImages("resize") { reads++; bytes }
            ) {
                Box(Modifier.requiredWidth(if (wide) 280.dp else 140.dp)) {
                    AttachmentImage(descriptor, markdown = true)
                }
            }
        } }
        rule.waitUntil(5000) {
            rule.onAllNodesWithContentDescription("Resize").fetchSemanticsNodes().isNotEmpty() &&
                ReaderBitmapBudget.shared.used == 481 * 311 * 4
        }
        rule.onNodeWithContentDescription("Resize").assertIsDisplayed()
        rule.runOnIdle { wide = true }
        rule.waitForIdle()
        rule.waitUntil(5000) {
            rule.onAllNodesWithContentDescription("Resize").fetchSemanticsNodes().isNotEmpty() &&
                ReaderBitmapBudget.shared.used == 962 * 622 * 4
        }
        rule.onNodeWithContentDescription("Resize").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, reads) }
    }

    @Test fun narrowInlineImageHasNoOverlayOrExtraHeight() {
        val image = Bitmap.createBitmap(480, 52, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        rule.setContent { Ot3Theme {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(3.4f),
                LocalReaderImages provides ReaderImages("tray") { bytes }
            ) {
                Box(Modifier.requiredWidth(360.dp).testTag("tray_container")) {
                    AttachmentImage(JSONObject().put("id", "tray").put("name", "Tray").put("source", "/tray.png"), markdown = true)
                }
            }
        } }
        rule.waitUntil(5000) { rule.onAllNodesWithContentDescription("Tray").fetchSemanticsNodes().isNotEmpty() }
        val imageBounds = rule.onNodeWithContentDescription("Tray").fetchSemanticsNode().boundsInRoot
        val container = rule.onNodeWithTag("tray_container").fetchSemanticsNode().boundsInRoot
        assertEquals(133f, imageBounds.height, 0f)
        assertEquals(imageBounds.height, container.height, 0f)
        rule.onNodeWithTag("thread_image_copy_thumbnail").assertDoesNotExist()
        rule.onNodeWithTag("thread_image_copy_menu").assertDoesNotExist()
    }

    @Test fun inlineSamplingPreservesPhysicalDisplayPixelsWithinBudget() {
        assertEquals(1, readerInlineImageSample(962, 622, 1510))
        assertEquals(1, readerInlineImageSample(962, 622, 640))
        assertEquals(2, readerInlineImageSample(962, 622, 480))
        assertEquals(1, readerInlineImageSample(962, 622, 960)) // rotation grows width
        assertEquals(4, readerInlineImageSample(8192, 4096, 1510))
        val sample = readerInlineImageSample(1000, 40000, 1510)
        assertTrue(readerImageAllocation(1000, 40000, sample) <= 16L * 1024 * 1024)
    }

    @Test fun extremeDimensionsStayInsidePixelBudgetAndInvalidDataFails() {
        for ((w, h) in listOf(100000 to 1, 1 to 100000, Int.MAX_VALUE to Int.MAX_VALUE, 8192 to 4096)) {
            val sample = readerImageSample(w, h, 640)
            assertTrue((w.toLong() + sample - 1) / sample <= 640)
            assertTrue((h.toLong() + sample - 1) / sample <= 640)
        }
        val original = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        original.recycle()
        var naturalWidth = 0
        val preview = decodeReaderImage(bytes, 640) { naturalWidth = it }
        assertEquals(4096, naturalWidth)
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
        rule.waitForIdle()
        rule.waitUntil(5000) { cancelled.isCompleted }
        rule.onNodeWithText("Close").assertDoesNotExist()
        rule.onNodeWithText("Retry").assertIsDisplayed()
    }
    @org.junit.Before fun resetClipboardProviderPaths() {
        val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (field.get(null) as MutableMap<*, *>).clear()
    }

    @Test fun copyIsExplicitBusyThenOriginalBytesAndGenerationCancelsPendingCopy() {
        val clipboard = rule.activity.getSystemService(android.content.ClipboardManager::class.java)
        val bytes = ByteArrayOutputStream().also { Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
            .compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        var generation by mutableStateOf("old")
        val copyStarted = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var reads = 0
        rule.runOnIdle { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "unchanged")) }
        rule.setContent { Ot3Theme { CompositionLocalProvider(LocalReaderImages provides ReaderImages(generation) {
            reads++
            if (reads == 2) {
                copyStarted.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            bytes
        }) { TimelineReader(JSONObject().put("timeline", org.json.JSONArray().put(row)), emptyList()) } } }
        rule.waitUntil(5000) { reads == 1 }
        rule.runOnIdle { assertEquals("unchanged", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
        rule.onNodeWithTag("thread_image_shot_640").performTouchInput { longClick() }
        rule.onNodeWithTag("thread_image_copy_menu").performClick()
        rule.waitUntil(5000) { copyStarted.isCompleted }
        rule.onNodeWithTag("thread_image_copy_menu").assertIsNotEnabled()
        rule.runOnIdle { generation = "new" }
        rule.waitForIdle()
        rule.waitUntil(5000) { cancelled.isCompleted }
        rule.runOnIdle { assertEquals("unchanged", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
        rule.onNodeWithTag("thread_image_shot_640").performTouchInput { longClick() }
        rule.onNodeWithTag("thread_image_copy_menu").performClick()
        rule.waitUntil(5000) { rule.waitForIdle(); clipboard.primaryClip?.getItemAt(0)?.uri != null }
        rule.onNodeWithContentDescription("Copied!").assertIsDisplayed()
        rule.runOnIdle {
            val uri = clipboard.primaryClip!!.getItemAt(0).uri!!
            assertArrayEquals(bytes, rule.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

}
