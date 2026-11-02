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
    private var shellBody = """{"snapshotSequence":3,"projects":[],"threads":[]}"""
    private val shellProtocol = AtomicReference<String?>("unset")

    @Before
    fun setUp() {
        assumeHostJni()
        server = FakeHttpServer { path, headers ->
            when (path) {
                "/fixture" ->
                    if (headers["authorization"] == "Bearer $TOKEN") FakeHttpServer.Response(200, "{\"text\":\"á 🦀\"}")
                    else FakeHttpServer.Response(401)
                "/api/auth/session" -> {
                    if (headers["authorization"] == "Bearer $TOKEN" && headers["x-t3-orchestration-protocol"] == null)
                        FakeHttpServer.Response(200, "{\"authenticated\":true,\"permissions\":[\"orchestration:operate\"]}")
                    else FakeHttpServer.Response(401)
                }
                "/api/orchestration/shell" -> {
                    shellProtocol.set(headers["x-t3-orchestration-protocol"])
                    if (headers["authorization"] == "Bearer $TOKEN") {
                        FakeHttpServer.Response(200, shellBody)
                    } else {
                        FakeHttpServer.Response(401)
                    }
                }
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
        if (::transport.isInitialized) runBlocking { transport.shutdown() }
        if (::server.isInitialized) server.close()
    }

    @Test
    fun binaryImageRequiresConnectedNativeSession() {
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { transport.image("attachment", "shot.png", "image/png") }
        }
        assertEquals("not_connected", error.code)
    }

    @Test
    fun binaryImageMetadataIsValidatedByRust() {
        runBlocking { transport.connect(ConnectRoute.Bearer(server.baseUrl, TOKEN)) }
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { transport.image("attachment", "shot.svg", "image/svg+xml") }
        }
        assertEquals("invalid_image", error.code)
    }

    @Test
    fun bearerRequestKeepsUnicode() = runBlocking {
        transport.connect(ConnectRoute.Bearer(server.baseUrl, TOKEN))

        val body = transport.request("/fixture") as JSONObject

        assertEquals("á 🦀", body.getString("text"))
    }

    @Test
    fun authSessionUsesBearerWithoutTheOrchestrationProtocolHeader() = runBlocking {
        transport.connect(ConnectRoute.Bearer(server.baseUrl, TOKEN))
        val session = transport.authSession()
        assertEquals(true, session.getBoolean("authenticated"))
        assertEquals("orchestration:operate", session.getJSONArray("permissions").getString(0))
    }

    @Test
    fun shellIsTheProtocolSnapshot() = runBlocking {
        transport.connect(ConnectRoute.Bearer(server.baseUrl, TOKEN))

        val shell = transport.shell()

        assertEquals(3L, shell.getLong("snapshotSequence"))
        assertEquals("2", shellProtocol.get())
    }

    @Test
    fun realisticShellPassesThroughTheAndroidAdapter() = runBlocking {
        val title = "x".repeat(2500 * 1024)
        shellBody = JSONObject().put("snapshotSequence", 4).put("projects", org.json.JSONArray())
            .put("threads", org.json.JSONArray().put(JSONObject().put("id", "large").put("title", title))).toString()
        transport.connect(ConnectRoute.Bearer(server.baseUrl, TOKEN))
        val shell = transport.shell()
        assertEquals(title.length, shell.getJSONArray("threads").getJSONObject(0).getString("title").length)
        val sidebar = dev.avillagran.ot3android.core.shell.SidebarView.build(
            transport, listOf(dev.avillagran.ot3android.core.shell.SidebarEnvironment(
                id = "fixture", shell = dev.avillagran.ot3android.core.shell.ShellReducer.parse(shell))), 0)
        assertEquals(title, sidebar.active.single().title)
    }

    @Test
    fun gzipAndBrotliAreDecodedThroughJni() = runBlocking {
        val body = "{\"snapshotSequence\":7,\"projects\":[],\"threads\":[]}".toByteArray()
        val gzip = java.io.ByteArrayOutputStream().also { output ->
            java.util.zip.GZIPOutputStream(output).use { it.write(body) }
        }.toByteArray()
        val brotli = java.util.Base64.getDecoder().decode("HzAA+I3UYs0Z7qQnS/35XQsmaBI2mgkd2IADhyyCgAbguwFHiMHJOUx6T9SOEgmhgRqRgg8=")
        for ((encoding, bytes) in listOf("gzip" to gzip, "br" to brotli)) {
            FakeHttpServer { _, headers ->
                org.junit.Assert.assertTrue(headers["accept-encoding"].orEmpty().contains(encoding))
                FakeHttpServer.Response(200, raw = bytes, encoding = encoding)
            }.use { compressed ->
                transport.connect(ConnectRoute.Bearer(compressed.baseUrl, TOKEN))
                assertEquals(7L, transport.shell().getLong("snapshotSequence"))
            }
        }
    }

    @Test
    fun shellNeedsAConnection() {
        val error = assertThrows(T3Exception::class.java) { runBlocking { transport.shell() } }

        assertEquals("not_connected", error.code)
    }

    @Test
    fun wrongTokenIsUnauthorized() {
        runBlocking { transport.connect(ConnectRoute.Bearer(server.baseUrl, "wrong-token-456")) }

        val error = assertThrows(T3Exception::class.java) { runBlocking { transport.shell() } }

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
