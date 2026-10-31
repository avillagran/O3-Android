package dev.avillagran.ot3android.core.pullrequests

import dev.avillagran.ot3android.core.transport.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PullRequestWritesTest {
    private val reference = JSONObject("""{"host":"git.test","projectId":"p","repository":"o/r","number":7}""")
    private val domain = DomainCalls { fn, args ->
        assertEquals("pullRequests.payload", fn)
        assertEquals(reference.toString(), args.getJSONObject("reference").toString())
        JSONObject().put("method", "pullRequests.comment").put("payload", args)
    }
    @Test fun unknownReceiptIsNotInventedOrRetried() = runBlocking {
        val transport = FakeT3Transport().also { it.connect(ConnectRoute.Bearer("http://fixture.test", "fixture")) }
        transport.onCall("pullRequests.comment") { JSONObject().put("unrecognised", true) }
        assertEquals(PullRequestWriteResult.Unknown, PullRequestWrites(domain).write(transport, reference, JSONObject().put("kind", "comment")))
        assertEquals(1, transport.calls.size)
    }
    @Test fun dispatchFailureDoesNotReplay() = runBlocking {
        val transport = FakeT3Transport().also { it.connect(ConnectRoute.Bearer("http://fixture.test", "fixture")) }
        transport.onCall("pullRequests.comment") { error("connection lost after dispatch") }
        assertEquals(PullRequestWriteResult.Unknown, PullRequestWrites(domain).write(transport, reference, JSONObject()))
        assertEquals(1, transport.calls.size)
    }
    @Test fun typedNullAcknowledgesSuccess() = runBlocking {
        val transport = FakeT3Transport().also { it.connect(ConnectRoute.Bearer("http://fixture.test", "fixture")) }
        transport.onCall("pullRequests.comment") { null }
        assertEquals(PullRequestWriteResult.Received, PullRequestWrites(domain).write(transport, reference, JSONObject()))
    }
    @Test fun validationFailureNeverDispatches() = runBlocking {
        val transport = FakeT3Transport()
        val invalid = PullRequestWrites(DomainCalls { _, _ -> error("invalid_comment") })
        try { invalid.write(transport, reference, JSONObject()); fail("must reject invalid payload") }
        catch (_: IllegalStateException) { assertTrue(transport.calls.isEmpty()) }
    }
    @Test fun badgeRetainsHostAndNativeStackDecision() {
        val badge = JSONObject().put("current", reference).put("kind", "stack")
        assertTrue(PullRequestBadgeAction.multiple(badge))
        assertEquals("git.test", PullRequestBadgeAction.reference(badge)!!.getString("host"))
        assertNull(PullRequestBadgeAction.reference(JSONObject()))
    }
}
