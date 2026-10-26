package dev.avillagran.ot3android.core.pullrequests

import dev.avillagran.ot3android.core.transport.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PullRequestReaderTest {
    @Before fun nativeLibrary() = assumeHostJni()
    @Test fun scopedListUsesNativeRecipeAndBadgeWithoutSorting() = runBlocking {
        val transport = FakeT3Transport().also { it.connect(ConnectRoute.Bearer("http://t3.test", "test")) }
        transport.onCall("pullRequests.list") { args ->
            assertEquals("p", args.getString("projectId"))
            assertEquals("merged", args.getString("state"))
            assertEquals("reviewing", args.getString("involvement"))
            assertEquals("rust", args.getString("query"))
            assertEquals(50, args.getInt("limit"))
            assertEquals("opaque", args.getJSONObject("cursors").getString("p"))
            JSONObject().put("entries", JSONArray().put(entry(8, "merged")).put(entry(3, "open")))
        }
        val result = PullRequestReader().list(transport, "merged", "reviewing", " rust ", "p", JSONObject().put("p", "opaque"))
        assertEquals(8, result.getJSONArray("entries").getJSONObject(0).getInt("number"))
        assertEquals("merged", result.getJSONArray("entries").getJSONObject(0).getJSONObject("badge").getString("state"))
        assertEquals("pullRequests.list", transport.calls.single().fn)
    }
    @Test fun detailChecksAndDiscussionPreserveHostAndProjectAndUseRustMarkdown() = runBlocking {
        val transport = FakeT3Transport().also { it.connect(ConnectRoute.Bearer("http://t3.test", "test")) }
        listOf("detail", "checks", "activity").forEach { action -> transport.onCall("pullRequests.$action") { args ->
            assertEquals("git.example.test", args.getString("host")); assertEquals("p", args.getString("projectId")); assertEquals("o/r", args.getString("repository")); assertEquals(7, args.getInt("number"))
            when (action) {
                "detail" -> JSONObject().put("body", "**Overview**")
                "activity" -> JSONObject("""{"comments":[{"body":"**Comment**"}],"reviewThreads":[{"comments":[{"body":"_Inline_"}]}]}""")
                else -> JSONObject().put("checks", JSONArray())
            }
        } }
        val reader = PullRequestReader()
        val ref = JSONObject("""{"projectId":"p","repository":"o/r","host":"git.example.test","number":7}""")
        val detail = reader.read(transport, "detail", ref)!!
        assertTrue(detail.getJSONArray("blocks").toString().contains("Overview"))
        reader.read(transport, "checks", ref)
        val activity = reader.read(transport, "activity", ref)!!
        assertTrue(activity.getJSONArray("comments").getJSONObject(0).has("blocks"))
        assertTrue(activity.getJSONArray("reviewThreads").getJSONObject(0).getJSONArray("comments").getJSONObject(0).has("blocks"))
        assertEquals(listOf("pullRequests.detail", "pullRequests.checks", "pullRequests.activity"), transport.calls.map { it.fn })
    }
    private fun entry(number: Int, state: String) = JSONObject().put("projectId", "p").put("repository", "o/r").put("host", "github.com").put("number", number).put("url", "https://github.com/o/r/pull/$number").put("state", state)
}
