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
 * The exact pins (0.3.2, 116 functions, 13 streams) are intentional: bumping
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
        assertEquals("0.3.2", capabilities.getString("libraryVersion"))
        val operations = capabilities.getJSONArray("operations").strings()
        assertTrue(operations.toString(), operations.containsAll(listOf("call", "describe", "wakeSubscriptions")))
    }

    @Test
    fun describeListsTheTypedRegistry() = runBlocking {
        val api = transport.describe()

        assertEquals(116, api.getJSONArray("functions").length())
        assertEquals(13, api.getJSONArray("streams").length())
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
