package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `pairBearer` through the real Rust core against a local server that serves the descriptor and
 * `POST /oauth/token`. The request asserted here is lib-t3-rust v0.3.2's `pair_bearer`
 * (src/pairing.rs): descriptor first, then a form-encoded RFC 8693 token exchange.
 */
class NativePairingTest {

    private lateinit var server: FakeHttpServer
    private lateinit var transport: NativeT3Transport
    private val requests = CopyOnWriteArrayList<FakeHttpServer.Request>()

    @Volatile private var descriptor = JSONObject().put("environmentId", ENVIRONMENT).put("label", "Fixture").put("serverVersion", "9.9.9")
    @Volatile private var tokenStatus = 200
    @Volatile private var tokenBody = JSONObject()
        .put("access_token", ACCESS_TOKEN)
        .put("token_type", "Bearer")
        .put("scope", "orchestration:read orchestration:operate")
        .put("expires_in", 2_592_000)
        .put("issued_token_type", "urn:ietf:params:oauth:token-type:access_token")

    @Before
    fun setUp() {
        assumeHostJni()
        server = FakeHttpServer.withRequests { request ->
            requests += request
            when (request.path) {
                "/.well-known/t3/environment" -> FakeHttpServer.Response(200, descriptor.toString())
                "/oauth/token" -> FakeHttpServer.Response(tokenStatus, tokenBody.toString())
                "/api/orchestration/shell" ->
                    if (request.headers["authorization"] == "Bearer $ACCESS_TOKEN") {
                        FakeHttpServer.Response(200, "{\"snapshotSequence\":1,\"projects\":[],\"threads\":[]}")
                    } else {
                        FakeHttpServer.Response(401)
                    }
                else -> FakeHttpServer.Response(404)
            }
        }
        transport = NativeT3Transport()
    }

    @After
    fun tearDown() {
        if (::transport.isInitialized) runBlocking { transport.shutdown() }
        if (::server.isInitialized) server.close()
    }

    @Test
    fun exchangesTheCodeForABearerSession() = runBlocking {
        val paired = transport.pairBearer(server.baseUrl, CODE, client = PairingClient(label = "ot3 test"))

        assertEquals(ACCESS_TOKEN, paired.accessToken)
        assertEquals("Bearer", paired.tokenType)
        assertEquals(ENVIRONMENT, paired.environmentId)
        assertEquals("Fixture", paired.descriptor.getString("label"))
        assertEquals("orchestration:read orchestration:operate", paired.scope)
        assertEquals(2_592_000L, paired.expiresIn)
        assertFalse(paired.connected)
        assertFalse(paired.toString().contains(ACCESS_TOKEN))

        assertEquals(listOf("GET /.well-known/t3/environment", "POST /oauth/token"), requests.map { "${it.method} ${it.path}" })
        assertTrue(requests.none { it.headers.containsKey("authorization") })
        val exchange = requests.last()
        assertEquals("application/x-www-form-urlencoded", exchange.headers["content-type"])
        assertEquals("application/json", exchange.headers["accept"])
        assertEquals(
            mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:token-exchange",
                "subject_token" to CODE,
                "subject_token_type" to "urn:t3:params:oauth:token-type:environment-bootstrap",
                "requested_token_type" to "urn:ietf:params:oauth:token-type:access_token",
                "client_label" to "ot3 test",
                "client_device_type" to "mobile",
                "client_os" to "android",
            ),
            form(exchange.body),
        )
        assertEquals("connect = false leaves the handle unconnected", "not_connected", failure { transport.shell() })
    }

    @Test
    fun connectTrueConnectsTheHandleWithTheNewToken() = runBlocking {
        val paired = transport.pairBearer(server.baseUrl, CODE, environmentId = ENVIRONMENT, connect = true)

        assertTrue(paired.connected)
        assertEquals(1L, transport.shell().getLong("snapshotSequence"))
    }

    @Test
    fun nullClientFieldsAreLeftOut() = runBlocking {
        transport.pairBearer(server.baseUrl, CODE, client = PairingClient(label = null, deviceType = null, os = null))

        val fields = form(requests.last().body)
        assertNull(fields["client_label"])
        assertNull(fields["client_device_type"])
        assertNull(fields["client_os"])
    }

    @Test
    fun aRefusedCodeIsPairingRejected() {
        for (status in listOf(400, 401, 403, 500)) {
            tokenStatus = status
            tokenBody = JSONObject().put("error", "invalid_grant")
            assertEquals("HTTP $status", "pairing_rejected", failure { transport.pairBearer(server.baseUrl, CODE) })
        }
    }

    @Test
    fun aTokenThatIsNotBearerIsUnsupported() {
        tokenBody.put("token_type", "DPoP")

        assertEquals("unsupported_pairing_token", failure { transport.pairBearer(server.baseUrl, CODE) })
    }

    @Test
    fun theDescriptorIsCheckedBeforeTheCodeIsSent() {
        assertEquals(
            "t3_environment_mismatch",
            failure { transport.pairBearer(server.baseUrl, CODE, environmentId = "00000000-0000-4000-8000-000000000000") },
        )
        descriptor = JSONObject().put("environmentId", "env-fixture")
        assertEquals("invalid_t3_environment_id", failure { transport.pairBearer(server.baseUrl, CODE) })

        assertTrue("the code was never posted", requests.none { it.method == "POST" })
    }

    @Test
    fun badInputFailsBeforeAnyRequest() {
        assertEquals("invalid_pairing_code", failure { transport.pairBearer(server.baseUrl, "  ") })
        assertEquals("invalid_pairing_code", failure { transport.pairBearer(server.baseUrl, "two words") })
        assertEquals(
            "invalid_pairing_client",
            failure { transport.pairBearer(server.baseUrl, CODE, client = PairingClient(deviceType = "phone")) },
        )
        assertEquals("invalid_endpoint", failure { transport.pairBearer("${server.baseUrl}/pair", CODE) })

        assertTrue(requests.isEmpty())
    }

    private fun failure(block: suspend () -> Unit): String =
        assertThrows(T3Exception::class.java) { runBlocking { block() } }.code

    private fun form(body: String): Map<String, String> = body.split('&').associate { pair ->
        URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
    }

    private companion object {
        const val ENVIRONMENT = "8b55eaad-3149-443c-953f-afd6dd002666"
        const val CODE = "fixture-pairing-code"
        const val ACCESS_TOKEN = "fixture-access-token-0123456789"
    }
}
