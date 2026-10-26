package dev.avillagran.ot3android.ui.thread

import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.testing.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test

class ReaderScreenshotTest : ScreenshotTest() {
    @Test fun markdownReader() {
        assumeHostJni()
        val view = runBlocking { DomainEngine.shared.domain("timeline.view", JSONObject().put("projection", JSONObject("""{
          "thread":{"id":"chat"},"messages":[
            {"id":"question","role":"user","createdAt":1,"text":"Keep the native reader close to OT3."},
            {"id":"answer","role":"assistant","createdAt":2,"text":"# Native reader\n\n**Rust** supplies *every block*. [Reference](https://example.org)\n\n- Select prose\n- Copy code\n\n```kotlin\nval thread = session.view.value\nprintln(thread.title)\n```\n\nReading position stays anchored."}
          ]
        }"""))) as JSONObject }
        snapshot("markdown") { TimelineReader(view, emptyList()) }
    }
}
