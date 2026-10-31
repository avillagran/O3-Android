package dev.avillagran.ot3android.core.git

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class GitReviewReaderTest {
    private class Fixture : DomainCalls {
        val scope = JSONObject("""{"environment":"host","thread":"t","projectId":"p","cwd":"/repo"}""")
        val requests = mutableListOf<Pair<String, JSONObject>>()
        var parsed: JSONObject? = null
        var lateScope = false
        var scopes = 0
        override suspend fun domain(fn: String, args: JSONObject): Any = when(fn) {
            "gitControls.scope" -> JSONObject(scope.toString()).apply { if (lateScope && ++scopes > 1) put("cwd", "/other") }
            "diffReview.present" -> { parsed = args; JSONObject().put("files", args.getJSONObject("summary").getJSONArray("files")) }
            else -> error(fn)
        }
        val transport = Proxy.newProxyInstance(T3Transport::class.java.classLoader, arrayOf(T3Transport::class.java)) { _, method, args ->
            when(method.name) {
                "shell" -> JSONObject()
                "call" -> {
                    val fn = args!![0] as String; val input = args[1] as JSONObject
                    requests += fn to JSONObject(input.toString())
                    if (fn == "vcs.listRefs") JSONObject().put("refs", JSONArray()).put("nextCursor", 50)
                    else JSONObject().put("cwd", "/repo").put("sources", JSONArray().put(JSONObject("""{"kind":"working-tree","diff":"native patch","truncated":false,"files":[{"path":"new.kt","previousPath":"old.kt","additions":1,"deletions":0},{"path":"other.kt","previousPath":null,"additions":0,"deletions":1}]}""")))
                }
                else -> error(method.name)
            }
        } as T3Transport
    }
    @Test fun perFileReadPassesNativeRenameAndWholeSummaryWithoutLocalParsing() = runTest {
        val f = Fixture()
        val view = GitReviewReader(f).read(f.transport, f.scope, "working-tree", null, true, "new.kt")
        assertEquals(2, f.requests.size)
        assertFalse(f.requests.first().second.has("file"))
        assertEquals("old.kt", f.requests.last().second.getJSONObject("file").getString("previousPath"))
        assertEquals(2, f.parsed!!.getJSONObject("summary").getJSONArray("files").length())
        assertEquals(2, view.getJSONArray("files").length())
    }
    @Test fun checkoutMovedDuringReadIsRejected() = runTest {
        val f = Fixture().also { it.lateScope = true }
        assertTrue(runCatching { GitReviewReader(f).read(f.transport, f.scope, "working-tree", null, true) }.isFailure)
    }
    @Test fun refsUseTypedCursorAndKindUnchanged() = runTest {
        val f = Fixture()
        val page = GitReviewReader(f).refs(f.transport, f.scope, "feature", "remote", 50)
        val call = f.requests.single()
        assertEquals("vcs.listRefs", call.first)
        assertEquals("remote", call.second.getString("refKind")); assertEquals(50, call.second.getLong("cursor"))
        assertEquals("feature", call.second.getString("query")); assertEquals(50, page.getInt("nextCursor"))
    }
}
