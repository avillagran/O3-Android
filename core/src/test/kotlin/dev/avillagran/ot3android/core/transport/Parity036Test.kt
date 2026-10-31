package dev.avillagran.ot3android.core.transport

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Parity036Test {
    @Before fun native() { assumeHostJni() }
    private suspend fun call(fn: String, args: JSONObject) = DomainEngine.shared.domain(fn, args) as JSONObject

    @Test fun emptyPermissionAdvertisementHasNativeFallback() = runBlocking {
        val view = call("models.permissionView", JSONObject().put("catalog", JSONObject()))
        assertEquals(4, view.getJSONArray("rows").length())
        assertEquals("approval-required", view.getJSONObject("current").getString("id"))
    }
    @Test fun staleHistoryOwnerCannotAdvanceCursor() = runBlocking {
        val result = call("history.mergeView", JSONObject().put("owner", "new").put("pageOwner", "old")
            .put("cursor", "a").put("pageCursor", "a").put("nextCursor", "b")
            .put("older", JSONArray()).put("recent", JSONArray()).put("page", JSONArray()))
        assertFalse(result.getBoolean("applied"))
        assertEquals("a", result.getString("cursor"))
    }
    @Test fun laterPrPageReplacesEarlierVersion() = runBlocking {
        val row = JSONObject().put("environmentId", "env").put("host", "GitHub.com").put("repository", "o/r").put("number", 1)
        val newer = JSONObject(row.toString()).put("title", "new")
        val result = call("pullRequests.listView", JSONObject().put("pages", JSONArray()
            .put(JSONObject().put("items", JSONArray().put(row))).put(JSONObject().put("items", JSONArray().put(newer)))))
        assertEquals(1, result.getJSONArray("items").length())
        assertEquals("new", result.getJSONArray("items").getJSONObject(0).getString("title"))
    }
    @Test fun splitAnsiAndCarriageReturnUseNativeState() = runBlocking {
        val reference = JSONObject().put("threadId", "t").put("itemId", "i").put("generation", 1)
        val first = call("commandOutput.apply", JSONObject().put("reference", reference).put("frames", JSONArray()
            .put(JSONObject().put("kind", "append").put("text", "\u001b[3"))))
        val second = call("commandOutput.apply", JSONObject().put("reference", reference).put("state", first.getJSONObject("state"))
            .put("frames", JSONArray().put(JSONObject().put("kind", "append").put("text", "1mold\rnew"))))
        assertEquals("new", second.getString("text"))
    }
    @Test fun composingTransformCannotEditText() = runBlocking {
        assertFalse(call("composer.transform", JSONObject().put("input", JSONObject().put("action", "list").put("text", "- a").put("cursor", 3).put("composing", true))).getBoolean("changed"))
    }
    @Test fun unsafeReferenceNeverBecomesAFileRead() = runBlocking {
        val result = NativeParity().reference("javascript:alert(1)", "env", "thread", JSONArray())
        assertEquals("unsupported", result.getString("kind"))
    }
}
