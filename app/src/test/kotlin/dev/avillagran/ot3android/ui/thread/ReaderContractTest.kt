package dev.avillagran.ot3android.ui.thread

import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.testing.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** JNI stays in the normal JVM classloader; UI tests read its verified output. */
class ReaderContractTest {
    @Test fun committedTableMatchesPinnedRust() = runBlocking {
        assumeHostJni()
        val markdown = checkNotNull(javaClass.getResource("/timeline/table.md")).readText()
        val expected = org.json.JSONArray(checkNotNull(javaClass.getResource("/timeline/table.blocks.json")).readText())
        val actual = DomainEngine.shared.domain("markdown.blocks", JSONObject().put("markdown", markdown))
        assertEquals(canonical(expected), canonical(actual))
    }

    @Test fun committedReaderViewsMatchPinnedRust() = runBlocking {
        assumeHostJni()
        for (name in listOf("reader", "golden")) {
            val projection = JSONObject(checkNotNull(javaClass.getResource("/timeline/$name.projection.json")).readText())
            val expected = JSONObject(checkNotNull(javaClass.getResource("/timeline/$name.view.json")).readText())
            val actual = DomainEngine.shared.domain("timeline.view", JSONObject().put("projection", projection)) as JSONObject
            assertEquals("Re-export $name after a deliberate Rust contract change", canonical(expected), canonical(actual))
        }
    }
}

private fun canonical(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
    is org.json.JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}
