package dev.avillagran.ot3android.ui.thread

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.navigation.LocalSelectedThreadKey
import dev.avillagran.ot3android.ui.theme.Ot3Theme
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
class ReaderImageZoomComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun descriptor(id: String) = JSONObject().put("id", id).put("name", "$id.png").put("type", "image").put("mimeType", "image/png")
    private fun image() = ByteArrayOutputStream().also { stream -> Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).also { it.compress(Bitmap.CompressFormat.PNG, 100, stream); it.recycle() } }.toByteArray()
    private fun scale() = rule.onNodeWithTag("thread_image_zoom_viewport").fetchSemanticsNode().config[SemanticsProperties.StateDescription].removeSuffix("%").toInt()
    @Test fun doubleTapPanResetAndReopenDoNotReadAgainUntilReopened() {
        var open by mutableStateOf(true)
        var reads = 0
        val bytes = image()
        rule.setContent { Ot3Theme { CompositionLocalProvider(LocalReaderImages provides ReaderImages("owner") { reads++; bytes }) {
            if (open) ReaderImageDialog(descriptor("first")) { open = false }
            else androidx.compose.material3.TextButton(onClick = { open = true }) { androidx.compose.material3.Text("Reopen") }
        } } }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("thread_image_zoom_viewport").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("thread_image_zoom_viewport").performTouchInput { doubleClick(center) }
        assertEquals(200, scale())
        rule.onNodeWithTag("thread_image_zoom_viewport").performTouchInput { swipe(center, center + Offset(100f, 0f)) }
        assertEquals(1, reads)
        rule.onNodeWithTag("thread_image_zoom_reset").performClick(); assertEquals(100, scale())
        rule.onNodeWithTag("thread_image_zoom_in").performClick(); assertEquals(200, scale())
        rule.onNodeWithTag("thread_image_zoom_out").performClick(); assertEquals(100, scale())
        rule.onNodeWithTag("thread_image_close").performClick()
        rule.onNodeWithText("Reopen").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("thread_image_zoom_viewport").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(100, scale()); assertEquals(2, reads)
    }
    @Test fun imageAndOwnerChangesResetButRecompositionDoesNot() {
        var id by mutableStateOf("first")
        var owner by mutableStateOf("env-a/thread")
        var generation by mutableStateOf("generation-a")
        var reads = 0
        val bytes = image()
        rule.setContent { Ot3Theme { CompositionLocalProvider(LocalSelectedThreadKey provides owner, LocalReaderImages provides ReaderImages(generation) { reads++; bytes }) {
            ReaderImageDialog(descriptor(id)) {}
        } } }
        fun loaded() { rule.waitUntil(5000) { rule.onAllNodesWithTag("thread_image_zoom_viewport").fetchSemanticsNodes().isNotEmpty() } }
        loaded(); rule.onNodeWithTag("thread_image_zoom_in").performClick(); assertEquals(200, scale())
        rule.runOnIdle { id = "second" }; loaded(); assertEquals(100, scale())
        rule.onNodeWithTag("thread_image_zoom_in").performClick()
        rule.runOnIdle { owner = "env-b/thread" }; loaded(); assertEquals(100, scale())
        rule.onNodeWithTag("thread_image_zoom_in").performClick()
        rule.runOnIdle { generation = "generation-b" }; loaded(); assertEquals(100, scale())
        assertEquals(4, reads)
    }
}
