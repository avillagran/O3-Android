package dev.avillagran.ot3android.core.surfaces

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DesktopSurfacesTest {
    private val report = JSONObject("""{"surface":"vault","reportId":"abc","report":{"configured":true}}""")
    private val proposal = JSONObject("""{"surface":"vault","action":"rollout","reportId":"abc"}""")
    private val confirmation = JSONObject().put("confirmationToken", "46ecba42-34cf-481c-99a0-5fa705f04d0e")
        .put("report", report).put("request", proposal).put("expiresIn", 60).put("retry", false)
    private fun http(body: JSONObject, status: Int = 200) = JSONObject().put("outcome", "http").put("httpStatus", status)
        .put("bodyBase64", java.util.Base64.getEncoder().encodeToString(body.toString().toByteArray()))
    private fun client(exchange: suspend (String, String, String?) -> JSONObject, clock: () -> Long = { 0L }) =
        DesktopSurfaces(exchange, clock) { java.util.Base64.getDecoder().decode(it) }
    private suspend fun refused(block: suspend () -> Unit) { assertTrue(runCatching { block() }.isFailure) }

    @Test fun exactPreparedReportAndRequestAreShownButNotToken() = runTest {
        val c = client({ method, path, body ->
            assertEquals("POST", method); assertEquals("/v1/maintenance/prepare", path)
            assertEquals("abc", JSONObject(body!!).getString("reportId")); http(confirmation)
        })
        val shown = c.prepare("vault", "rollout", "abc")
        assertEquals(report.toString(), shown.getJSONObject("report").toString())
        assertEquals(proposal.toString(), shown.getJSONObject("request").toString())
        assertFalse(shown.has("confirmationToken"))
    }
    @Test fun disabledConsentShowsNoCachedReport() = runTest {
        val c = client({ _, _, _ -> http(JSONObject().put("error", "consent_required"), 403) })
        refused { c.read("/v1/memory") }; refused { c.confirm() }
    }
    @Test fun deniedAndRevokedNeverReachConfirmation() = runTest {
        for (status in listOf(401, 403, 404)) {
            var calls = 0
            val c = client({ _, _, _ -> calls++; http(JSONObject().put("error", "unauthorized"), status) })
            refused { c.prepare("vault", "rollout", "abc") }; refused { c.confirm() }; assertEquals(1, calls)
        }
    }
    @Test fun expiredConfirmationIsConsumedWithoutSending() = runTest {
        var now = 0L; var calls = 0
        val c = client({ _, _, _ -> calls++; http(confirmation) }, { now })
        c.prepare("vault", "rollout", "abc"); now = 60000
        refused { c.confirm() }; refused { c.confirm() }; assertEquals(1, calls)
    }
    @Test fun staleReportRefusalIsNotRetried() = runTest {
        var calls = 0
        val c = client({ _, _, _ -> calls++; if (calls == 1) http(confirmation) else http(JSONObject().put("error", "stale_report"), 409) })
        c.prepare("vault", "rollout", "abc"); refused { c.confirm() }; refused { c.confirm() }
        assertEquals(2, calls); assertFalse(c.unknown)
    }
    @Test fun transportUnknownBlocksNewMutationsEvenAfterRead() = runTest {
        var posts = 0
        val c = client({ method, _, _ -> if (method == "GET") http(report) else {
            posts++; if (posts == 1) http(confirmation) else JSONObject().put("outcome", "unknown")
        } })
        c.prepare("vault", "rollout", "abc"); refused { c.confirm() }; c.read("/v1/vault")
        assertTrue(c.unknown); refused { c.prepare("vault", "rollout", "abc") }; refused { c.confirm() }; assertEquals(2, posts)
    }
    @Test fun hostUnknownIsAlsoPreserved() = runTest {
        var calls = 0
        val c = client({ _, _, _ -> calls++; http(if (calls == 1) confirmation else JSONObject().put("outcome", "unknown").put("retry", false)) })
        c.prepare("vault", "rollout", "abc"); c.confirm(); assertTrue(c.unknown); refused { c.confirm() }; assertEquals(2, calls)
    }
    @Test fun notSentStillConsumesConfirmation() = runTest {
        var calls = 0
        val c = client({ _, _, _ -> calls++; if (calls == 1) http(confirmation) else JSONObject().put("outcome", "notSent") })
        c.prepare("vault", "rollout", "abc"); refused { c.confirm() }; refused { c.confirm() }
        assertFalse(c.unknown); assertEquals(2, calls)
    }
    @Test fun cancellationAfterSendRemainsUnknown() = runTest {
        var calls = 0
        val c = client({ _, _, _ -> calls++; if (calls == 1) http(confirmation) else throw kotlinx.coroutines.CancellationException() })
        c.prepare("vault", "rollout", "abc"); refused { c.confirm() }; assertTrue(c.unknown); refused { c.confirm() }
    }
    @Test fun changedPreparedRequestIsRejected() = runTest {
        val changed = JSONObject(confirmation.toString()).put("request", JSONObject().put("surface", "build-cleanup"))
        val c = client({ _, _, _ -> http(changed) })
        refused { c.prepare("vault", "rollout", "abc") }; refused { c.confirm() }
    }
}
