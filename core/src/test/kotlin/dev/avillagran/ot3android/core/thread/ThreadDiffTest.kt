package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.assumeHostJni
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ThreadDiffTest {
    @Before fun nativeLibrary() = assumeHostJni()
    private val summary = JSONObject("""{"fromTurnCount":2,"toTurnCount":3,"files":[{"path":"a.kt","kind":"modified","additions":1,"deletions":1},{"path":"missing.kt","kind":"modified"}]}""")
    private fun response() = JSONObject().put("threadId", "thread").put("fromTurnCount", 2).put("toTurnCount", 3)
        .put("diff", "diff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n-old\n+new\n")

    @Test fun readsTypedRangeAndUsesNativeRowsAndMissingFiles() = runBlocking {
        val fake = FakeT3Transport().also { it.connect(ConnectRoute.Bearer("http://t3.test", "test-token")) }
        fake.onCall("orchestration.getTurnDiff") { response() }
        val view = ThreadDiff.read(fake, "env", "thread", summary, true)!!
        val call = fake.calls.single()
        assertEquals("orchestration.getTurnDiff", call.fn)
        assertEquals(2, call.args.getInt("fromTurnCount"))
        assertEquals(3, call.args.getInt("toTurnCount"))
        assertTrue(call.args.getBoolean("ignoreWhitespace"))
        val files = view.getJSONArray("files")
        assertEquals(2, files.length())
        assertTrue(files.getJSONObject(1).getBoolean("missing"))
        val rows = files.getJSONObject(0).getJSONArray("rows")
        val added = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("kind") == "addition" }
        assertEquals("+new", added.getString("text"))
        assertEquals(1, added.getInt("newLine"))
    }

    @Test fun wrongThreadOrRangeIsRejectedByRust() = runBlocking {
        for ((key, value) in listOf("threadId" to "another", "toTurnCount" to 99)) {
            val fake = FakeT3Transport().also { it.connect(ConnectRoute.Bearer("http://t3.test", "test-token")) }
            fake.onCall("orchestration.getTurnDiff") { response().put(key, value) }
            try {
                ThreadDiff.read(fake, "env", "thread", summary, false)
                fail("Mismatched response must not be rendered")
            } catch (error: T3Exception) {
                assertTrue(error.code in setOf("invalid_diff_thread", "invalid_diff_range"))
            }
        }
    }
}
