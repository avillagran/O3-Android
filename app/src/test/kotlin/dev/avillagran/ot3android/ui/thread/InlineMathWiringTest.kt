package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class InlineMathWiringTest {
    @Test fun settingPreservesSourceAndTheOrderOfSurroundingText() {
        val spans = JSONArray("""[{"text":"before "},{"type":"math","tex":"\\alpha"},{"text":" after"}]""")
        assertEquals("before α after", inlineSpans(spans, true).text)
        assertEquals("before \\alpha after", inlineSpans(spans, false).text)
    }
    @Test fun codeTintUsesTheSurfaceWithoutChangingFontOrSize() {
        val tint = androidx.compose.ui.graphics.Color(0xff334455)
        val text = inlineSpans(JSONArray("""[{"type":"code","text":"caption.png"}]"""), codeBackground = tint)
        val style = text.spanStyles.single().item
        assertEquals(tint, style.background)
        assertEquals(dev.avillagran.ot3android.ui.theme.FiraCode, style.fontFamily)
        assertEquals(androidx.compose.ui.unit.TextUnit.Unspecified, style.fontSize)
    }

    @Test fun paragraphSpacingFollowsCollapsedMarginsAndImageLineLeading() {
        val prose = org.json.JSONObject("""{"type":"paragraph","spans":[{"type":"text","text":"Section","strong":true}]}""")
        val imageCaption = org.json.JSONObject("""{"type":"paragraph","spans":[{"type":"image","url":"/shot.png"},{"type":"code","text":"shot.png"}]}""")
        assertEquals(17.4f, markdownBlockGap(prose, imageCaption), .001f)
        assertEquals(10.4f, markdownBlockGap(imageCaption, prose), .001f)
        assertEquals(10.4f, markdownBlockGap(imageCaption, imageCaption), .001f)
    }

}
