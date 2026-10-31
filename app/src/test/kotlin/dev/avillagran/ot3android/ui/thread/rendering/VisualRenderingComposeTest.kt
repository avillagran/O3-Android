package dev.avillagran.ot3android.ui.thread.rendering

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.thread.MarkdownTable
import dev.avillagran.ot3android.ui.thread.ReaderBitmapBudget
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("DEPRECATION")
class VisualRenderingComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun codeCopyIsExactAndUnsupportedPreviewIsHidden() {
        var copied: (() -> String?)? = null
        val code = "graph TD; A[\"<literal>\"]-->B\n"
        rule.setContent { Ot3Theme {
            val clipboard = LocalClipboardManager.current
            copied = { clipboard.getText()?.text }
            VisualCodeBlock(JSONObject().put("language", "mermaid").put("code", code), 3)
        } }
        rule.onNodeWithTag("thread_copy_code_3").performClick()
        rule.runOnIdle { assertEquals(code, copied?.invoke()) }
        rule.onNodeWithTag("visual_toggle_3").assertDoesNotExist()
        rule.onNodeWithTag("thread_code_3").assertExists()
    }
    @Test fun streamingNeverOffersPreviewOrRun() {
        rule.setContent { Ot3Theme {
            CompositionLocalProvider(LocalCodeActions provides CodeActions { error("must not execute") }) {
                VisualCodeBlock(JSONObject().put("language", "qml").put("code", "Item {}").put("command", "echo test"), 0, streaming = true)
            }
        } }
        rule.onNodeWithTag("visual_toggle_0").assertDoesNotExist()
        rule.onNodeWithText("Run in terminal").assertDoesNotExist()
        rule.onNodeWithTag("thread_code_0").assertExists()
    }
    @Test fun tableCopiesNativeFormatsUnchanged() {
        var copied: (() -> String?)? = null
        val block = JSONObject("""{"header":[[{"text":"Name"}],[{"text":"Value"}]],"rows":[[[{"text":"a, b"}],[{"text":"π"}]]],"alignments":["left","right"],"csv":"Name,Value\n\"a, b\",π","markdown":"| Name | Value |\n| --- | --- |\n| a, b | π |"}""")
        rule.setContent { Ot3Theme {
            val clipboard = LocalClipboardManager.current
            copied = { clipboard.getText()?.text }
            MarkdownTable(block, 2)
        } }
        rule.onNodeWithTag("thread_copy_table_csv_2").performClick()
        rule.runOnIdle { assertEquals(block.getString("csv"), copied?.invoke()) }
        rule.onNodeWithTag("thread_copy_table_markdown_2").performClick()
        rule.runOnIdle { assertEquals(block.getString("markdown"), copied?.invoke()) }
    }
    @Test fun rasterFrameUsesBoundedReaderAndReleasesAfterScopeExpires() {
        var availability by mutableStateOf(VisualAvailability.Ready)
        val image = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        rule.setContent { Ot3Theme {
            CompositionLocalProvider(LocalVisualRenderer provides VisualRenderer("scope", availability = availability, isCurrent = { availability == VisualAvailability.Ready }, render = { bytes })) {
                VisualPreview(VisualRequest(VisualKind.Mermaid, "graph TD; A-->B", "Fixture diagram"), "test")
            }
        } }
        rule.waitUntil(5000) { rule.onAllNodesWithContentDescription("Fixture diagram").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(ReaderBitmapBudget.shared.used in 1..ReaderBitmapBudget.shared.limit)
        rule.runOnIdle { availability = VisualAvailability.Expired }
        rule.onNodeWithText("Preview permission expired").assertExists()
        rule.onNodeWithContentDescription("Fixture diagram").assertDoesNotExist()
        rule.waitUntil(5000) { ReaderBitmapBudget.shared.used == 0 }
    }
}
