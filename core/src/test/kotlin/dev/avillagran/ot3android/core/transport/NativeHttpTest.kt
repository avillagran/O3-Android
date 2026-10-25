package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Bearer connection and HTTP through the real Rust core against a local JDK HTTP server. */
class NativeHttpTest {

    private lateinit var server: FakeHttpServer
    private lateinit var transport: NativeT3Transport
    private val descriptorAuthorization = AtomicReference<String?>("unset")

    @Before
    fun setUp() {
        assumeHostJni()
        server = FakeHttpServer { path, headers ->
            when (path) {
                "/fixture" ->
                    if (headers["authorization"] == "Bearer $TOKEN") FakeHttpServer.Response(200, "{\"text\":\"á 🦀\"}")
                    else FakeHttpServer.Response(401)
                "/.well-known/t3/environment" -> {
                    descriptorAuthorization.set(headers["authorization"])
                    FakeHttpServer.Response(200, "{\"environmentId\":\"env-fixture\",\"label\":\"Fixture\"}")
                }
                else -> FakeHttpServer.Response(404)
            }
        }
        transport = NativeT3Transport()
    }

    @After
    fun tearDown() {
        if (::transport.isInitialized) transport.close()
        if (::server.isInitialized) server.close()
    }

    @Test
    fun bearerRequestKeepsUnicode() = runBlocking {
        transport.connect(ConnectRoute.Bearer(server.baseUrl, TOKEN))

        val body = transport.request("/fixture") as JSONObject

        assertEquals("á 🦀", body.getString("text"))
    }

    @Test
    fun wrongTokenIsUnauthorized() {
        runBlocking { transport.connect(ConnectRoute.Bearer(server.baseUrl, "wrong-token-456")) }

        val error = assertThrows(T3Exception::class.java) { runBlocking { transport.request("/fixture") } }

        assertEquals("unauthorized", error.code)
    }

    @Test
    fun descriptorNeedsNoConnectionNorCredential() = runBlocking {
        val descriptor = transport.fetchDescriptor(server.baseUrl)

        assertEquals("env-fixture", descriptor.getString("environmentId"))
        assertNull(descriptorAuthorization.get())
    }

    @Test
    fun descriptorForAnotherEnvironmentIsRefused() {
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { transport.fetchDescriptor(server.baseUrl, environmentId = "env-other") }
        }
        assertEquals("t3_environment_mismatch", error.code)
    }

    @Test
    fun connectionOptionsAreValidatedByTheLib() {
        val bearer = assertThrows(T3Exception::class.java) {
            runBlocking { transport.connect(ConnectRoute.Bearer(server.baseUrl, TOKEN, JSONObject().put("retries", 3))) }
        }
        val relay = assertThrows(T3Exception::class.java) {
            runBlocking {
                transport.connect(
                    ConnectRoute.Relay(
                        "https://relay.invalid", "jwt", "env-1",
                        options = JSONObject().put("requestTimeoutMs", 0),
                    ),
                )
            }
        }
        assertEquals("invalid_connection_options", bearer.code)
        assertEquals("invalid_connection_options", relay.code)
    }

    private companion object {
        const val TOKEN = "fixture-token-123"
    }
}
