package dev.avillagran.ot3android.ui.thread

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.FiraCode
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-port-xhdpi")
class ReaderWebLinksTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun blocks() = JSONArray(checkNotNull(javaClass.getResource("/timeline/links.blocks.json")).readText())
    private val opened = mutableListOf<String>()
    private fun show(data: JSONArray) {
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) { Ot3Theme { MarkdownBlocks(data) } }
        }
    }

    private fun clickText(label: String) {
        val node = rule.onNodeWithText(label)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        node.performTouchInput { click(layouts.single().getBoundingBox(label.length / 2).center) }
    }

    @Test fun webAndGithubOpenWithOnlyPlatformSchemeNormalization() {
        val native = blocks()
        val original = native.toString()
        show(JSONArray((0..2).map { native.getJSONObject(it) }))
        for (label in listOf("Mixed web", "Mixed GitHub", "Plain web")) clickText(label)
        rule.runOnIdle {
            assertEquals(listOf("https://Example.COM/Path?Key=VaL#Frag", "https://GitHub.COM/Owner/Repo", "http://example.com/plain"), opened)
            assertEquals(original, native.toString())
        }
    }

    @Test fun tableCellsUseTheSameNativeLinkClassification() {
        val table = blocks().getJSONObject(10)
        show(JSONArray().put(table))
        clickText("Table web")
        clickText("Table file")
        rule.runOnIdle { assertEquals(listOf("http://Example.COM/Table?Case=Keep"), opened) }
    }

    @Test fun nativeNonWebAndRejectedSchemesRemainNonClickable() {
        val native = blocks()
        show(JSONArray((4..9).map { native.getJSONObject(it) }))
        for (index in 4..9) {
            val spans = native.getJSONObject(index).getJSONArray("spans")
            val text = inlineSpans(spans)
            assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
            rule.onNodeWithText(text.text).performClick()
        }
        rule.runOnIdle { assertTrue(opened.isEmpty()) }
    }

    @Test fun nativeKindsAreRequiredAndInlineFormattingSurvivesLinks() {
        val unknown = JSONArray("""[{"text":"future","link":{"kind":"future","href":"https://example.com"}},{"text":"missing kind","link":{"href":"https://example.com"}}]""")
        val unclassified = inlineSpans(unknown)
        assertTrue(unclassified.getLinkAnnotations(0, unclassified.length).isEmpty())
        val styled = inlineSpans(blocks().getJSONObject(3).getJSONArray("spans"))
        assertEquals("Strong emphasis code strike", styled.text)
        fun style(label: String) = styled.spanStyles.single { it.start == styled.text.indexOf(label) && it.end == styled.text.indexOf(label) + label.length }.item
        assertEquals(FontWeight.Bold, style("Strong").fontWeight)
        assertEquals(FontStyle.Italic, style("emphasis").fontStyle)
        assertEquals(FiraCode, style("code").fontFamily)
        assertEquals(TextDecoration.LineThrough, style("strike").textDecoration)
        assertEquals(7, styled.getLinkAnnotations(0, styled.length).size)
        assertTrue(styled.getLinkAnnotations(0, styled.length).all { (it.item as LinkAnnotation.Url).url == "https://example.com/styled" })
    }
}
