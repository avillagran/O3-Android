package dev.avillagran.ot3android.ui.thread.reader

import android.app.Application
import org.json.JSONObject
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TranscriptNavigationTest {
    @Test fun matchesAreOccurrencesAndKeepStableRowIdentityAcrossStreaming() {
        val older = JSONObject("""{"id":"old","role":"user","text":"Alpha alpha"}""")
        val live = JSONObject("""{"id":"live","role":"assistant","text":"Al","streaming":true}""")
        val before = transcriptMatches(listOf(live, older), "alpha")
        assertEquals(listOf(TranscriptMatch("old", 0), TranscriptMatch("old", 6)), before)
        live.put("text", "Alpha")
        val after = transcriptMatches(listOf(live, older), "ALPHA")
        assertEquals(before, after.takeLast(2))
        assertEquals(TranscriptMatch("live", 0), after.first())
    }
    @Test fun excludesSecretsAndRawToolsEvenWhenTheyContainText() {
        val rows = listOf("secret", "activity", "error").map { JSONObject().put("role", it).put("text", "needle") }
        assertTrue(transcriptMatches(rows, "needle").isEmpty())
        assertTrue(transcriptMatches(rows, " ").isEmpty())
    }
    @Test fun planSearchAndNullEndpoints() {
        val plan = JSONObject("""{"id":"p","role":"plan","text":"","plan":{"markdown":"# Strategy"}}""")
        assertEquals(listOf(TranscriptMatch("p", 2)), transcriptMatches(listOf(plan), "Strategy"))
        assertEquals("one / model → two", contextEndpoints(JSONObject("""{"from":[{"instanceId":"one","model":"model"}],"to":{"instanceId":"two","model":null}}""")))
        assertEquals("", JSONObject().put("id", JSONObject.NULL).readerText("id"))
    }
    @Test fun secureDeliveryUsesOneCallClearsPayloadAndNeverRecordsSecret() = runTest {
        val base = FakeT3Transport()
        var count = 0
        var retained: JSONObject? = null
        val transport = object : T3Transport by base {
            override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
                count++
                assertEquals("secrets.answerRequest", fn)
                assertEquals("thread", args.getString("threadId"))
                assertEquals("item", args.getString("turnItemId"))
                assertEquals("test-value", args.getJSONObject("answer").getString("secret"))
                retained = args
                throw IllegalStateException("untrusted error with test-value")
            }
        }
        val value = "test-value".toCharArray()
        val controller = TranscriptSecrets(transport)
        try { controller.answer("thread", "item", value); fail("must propagate") }
        catch (_: IllegalStateException) { /* UI maps all failures to a fixed resource. */ }
        val repeated = "second-value".toCharArray()
        controller.answer("thread", "item", repeated)
        assertTrue(repeated.all { it == '\u0000' })
        assertEquals(SecretDelivery.Uncertain, controller.deliveries.value["thread/item"])
        assertEquals(1, count)
        assertTrue(value.all { it == '\u0000' })
        assertFalse(retained.toString().contains("test-value"))
        assertTrue(base.log.isEmpty())
    }

}
