package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The real Rust core through the AAR's adapter and the host JNI library; no network.
 *
 * The exact pins (0.3.3, 116 functions, 13 streams, 37 domain functions) are intentional: bumping
 * libs/lib-t3-rust.lock must fail here until the lib's changes are read and the pins move
 * with them (docs/DECISIONS.md).
 */
class NativeSmokeTest {

    private lateinit var transport: NativeT3Transport

    @Before
    fun setUp() {
        assumeHostJni()
        transport = NativeT3Transport()
    }

    @After
    fun tearDown() {
        if (::transport.isInitialized) runBlocking { transport.shutdown() }
    }

    @Test
    fun capabilitiesReportTheTargetedLib() = runBlocking {
        val capabilities = transport.capabilities()

        assertEquals(3, capabilities.getInt("abiVersion"))
        assertEquals("0.3.3", capabilities.getString("libraryVersion"))
        assertEquals(116, capabilities.getInt("functions"))
        assertEquals(37, capabilities.getInt("domainFunctions"))
        val operations = capabilities.getJSONArray("operations").strings()
        assertEquals(operations.toString(), 19, operations.size)
        assertTrue(operations.toString(), operations.containsAll(listOf("call", "describe", "domain", "wakeSubscriptions")))
    }

    @Test
    fun describeListsTheTypedRegistry() = runBlocking {
        val api = transport.describe()

        assertEquals(116, api.getJSONArray("functions").length())
        assertEquals(13, api.getJSONArray("streams").length())
        val domain = api.getJSONArray("domain")
        assertEquals(37, domain.length())
        val names = List(domain.length()) { domain.getJSONObject(it).getString("fn") }
        assertTrue(names.toString(), names.containsAll(listOf("projection.apply", "timeline.view", "sidebar.view", "projects.identity")))
    }

    @Test
    fun callValidatesArgumentsBeforeConnecting() {
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { transport.call("vcs.refreshStatus", JSONObject().put("cwd", "")) }
        }
        assertEquals("invalid_cwd", error.code)
    }

    @Test
    fun unknownFunctionIsAStableCode() {
        val error = assertThrows(T3Exception::class.java) { runBlocking { transport.call("vcs.nope") } }
        assertEquals("unknown_function", error.code)
    }

    @Test
    fun validCallWithoutConnectionIsNotConnected() {
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { transport.call("vcs.refreshStatus", JSONObject().put("cwd", "/repo")) }
        }
        assertEquals("not_connected", error.code)
    }

    @Test
    fun invalidStreamArgumentsFailBeforeAnySocket() {
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { transport.threadStream("").collect {} }
        }
        assertEquals("invalid_thread_id", error.code)
    }

    private fun JSONArray.strings() = List(length()) { getString(it) }
}
