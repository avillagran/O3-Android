package dev.avillagran.ot3android.core.usage

import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UsageReaderTest {
    @Before fun nativeLibrary() = assumeHostJni()

    @Test fun requestAndPresentationComeFromNativeContract() = runBlocking {
        val transport = FakeT3Transport().also { it.connect(ConnectRoute.Bearer("http://t3.test", "test")) }
        transport.onCall("server.getUsageSummary") {
            JSONObject("""{"buckets":[{"day":"2026-10-26","provider":"codex","model":"gpt","totals":{"uncachedInputTokens":100,"cachedInputTokens":40,"outputTokens":20},"costUsd":1.25,"cacheSavingsUsd":0.2,"sessions":2,"records":3}],"pricing":{"status":"fresh"}}""")
        }
        val summary = UsageReader.read(transport, "week", "2026-10-26", "America/Santiago")
        val request = transport.calls.single()
        assertEquals("server.getUsageSummary", request.fn)
        assertEquals("2026-10-20", request.args.getString("sinceDay"))
        assertEquals("2026-10-26", request.args.getString("untilDay"))
        assertEquals("America/Santiago", request.args.getString("timeZone"))
        assertEquals(160, summary.getInt("tokens"))
        assertEquals(1.25, summary.getDouble("cost"), 0.0)
        assertEquals(7, summary.getJSONArray("days").length())
        assertEquals(0, summary.getJSONArray("days").getJSONObject(0).getInt("tokens"))
        assertEquals("gpt", summary.getJSONArray("models").getJSONObject(0).getString("model"))
    }
}
