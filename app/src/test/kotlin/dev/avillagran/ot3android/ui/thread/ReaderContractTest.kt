package dev.avillagran.ot3android.ui.thread

import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.testing.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** JNI stays in the normal JVM classloader; UI tests read its verified output. */
class ReaderContractTest {
    @Test fun usageFixtureMatchesPinnedRust() = runBlocking {
        assumeHostJni()
        val input = JSONObject(checkNotNull(javaClass.getResource("/usage/summary.input.json")).readText())
        val expected = JSONObject(checkNotNull(javaClass.getResource("/usage/summary.view.json")).readText())
        assertEquals(canonical(expected), canonical(DomainEngine.shared.domain("usageSummary.aggregate", input)))
    }

    @Test fun diffFixtureMatchesPinnedRust() = runBlocking {
        assumeHostJni()
        val input = JSONObject(checkNotNull(javaClass.getResource("/timeline/diff.input.json")).readText())
        val expected = JSONObject(checkNotNull(javaClass.getResource("/timeline/diff.view.json")).readText())
        assertEquals(canonical(expected), canonical(DomainEngine.shared.domain("diffReview.present", input)))
    }

    @Test fun nativeSplitKeepsUnequalChangeSidesNull() = runBlocking {
        assumeHostJni()
        val patch = "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1,1 +1,2 @@\n-old\n+new\n+extra\n@@ -9,1 +10,0 @@\n-gone\n"
        val view = DomainEngine.shared.domain("diffReview.present", JSONObject().put("result", JSONObject().put("diff", patch)).put("summary", JSONObject())) as JSONObject
        val rows = view.getJSONArray("files").getJSONObject(0).getJSONArray("splitRows")
        assertEquals("-old", rows.getJSONObject(3).getJSONObject("left").getString("text"))
        assertEquals("+new", rows.getJSONObject(3).getJSONObject("right").getString("text"))
        org.junit.Assert.assertTrue(rows.getJSONObject(4).isNull("left"))
        assertEquals(2, rows.getJSONObject(4).getJSONObject("right").getInt("newLine"))
        org.junit.Assert.assertTrue(rows.getJSONObject(6).isNull("right"))
        assertEquals(9, rows.getJSONObject(6).getJSONObject("left").getInt("oldLine"))
    }

    @Test fun nativeMarkdownLinksKeepOriginalHrefKindsAndRejectedDestinations() = runBlocking {
        assumeHostJni()
        val markdown = checkNotNull(javaClass.getResource("/timeline/links.md")).readText()
        val expected = org.json.JSONArray(checkNotNull(javaClass.getResource("/timeline/links.blocks.json")).readText())
        val actual = DomainEngine.shared.domain("markdown.blocks", JSONObject().put("markdown", markdown)) as org.json.JSONArray
        assertEquals(canonical(expected), canonical(actual))
        val web = actual.getJSONObject(0).getJSONArray("spans").getJSONObject(0).getJSONObject("link")
        assertEquals("web", web.getString("kind"))
        assertEquals("HtTpS://Example.COM/Path?Key=VaL#Frag", web.getString("href"))
        assertEquals("github", actual.getJSONObject(1).getJSONArray("spans").getJSONObject(0).getJSONObject("link").getString("kind"))
        for (index in 7..9) org.junit.Assert.assertFalse(actual.getJSONObject(index).getJSONArray("spans").getJSONObject(0).has("link"))
        val cell = actual.getJSONObject(10).getJSONArray("rows").getJSONArray(0).getJSONArray(0).getJSONObject(0).getJSONObject("link")
        assertEquals("web", cell.getString("kind"))
        assertEquals("hTtP://Example.COM/Table?Case=Keep", cell.getString("href"))
    }

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
    is Number -> value.toString().toBigDecimal().stripTrailingZeros().toPlainString()
    is String -> JSONObject.quote(value)
    else -> value.toString()
}
