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
}
