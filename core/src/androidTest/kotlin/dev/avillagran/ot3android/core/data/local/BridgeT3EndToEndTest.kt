package dev.avillagran.ot3android.core.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.core.bridge.BridgeCatalogController
import dev.avillagran.ot3android.core.bridge.NativeBridge
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ShellCursorStore
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.security.TokenStore
import dev.avillagran.ot3android.core.transport.NativeT3TransportFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Android/JNI -> desktop bridge -> separate QUIC issuer -> REAL isolated T3 shells over TLS. */
class BridgeT3EndToEndTest {
    @Test fun twoRealIsolatedServersEnforceReadScopeExpiryAndRevocation() = runBlocking {
        val port = InstrumentationRegistry.getArguments().getString("realT3LabPort")
        assumeTrue("Requires catalog_t3_lab and explicitly built lab-tls JNI", port != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        suspend fun lab(path: String, payload: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
            val connection = URL("http://10.0.2.2:$port$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000; connection.readTimeout = 15000
            if (payload != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            }
            try { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) }
            finally { connection.disconnect() }
        }
        val configuration = lab("/invitation")
        val issuerBridge = configuration.getString("issuerBridgeId")
        val delegatedEnvironment = configuration.getString("delegatedEnvironmentId")
        assertNotEquals("Delegated issuer must be a separate bridge identity",
            configuration.getJSONObject("invitation").getJSONObject("address").getString("id"), issuerBridge)
        val database = Room.inMemoryDatabaseBuilder(context, Ot3Database::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secrets = mutableMapOf<String, String>()
        val tokens = object : TokenStore {
            override suspend fun token(environmentId: String) = secrets[environmentId]
            override suspend fun setToken(environmentId: String, token: String) { secrets[environmentId] = token }
            override suspend fun delete(environmentId: String) { secrets.remove(environmentId) }
        }
        val cursors = object : ShellCursorStore {
            override suspend fun cursor(environmentId: String): Long? = null
            override suspend fun setCursor(environmentId: String, cursor: Long?) = Unit
        }
        val repository = EnvironmentRepository(database.environmentDao(), { tokens }, cursors, managed = database.bridgeManagedDao())
        val connections = ConnectionManager(repository, cursors, NativeT3TransportFactory(), scope)
        var handle = 0L
        val controller = BridgeCatalogController(database, connections, scope, { tokens }, native = { request ->
            if (request.getString("op") == "open") request.put("labCaPem", configuration.getString("labCaPem"))
            NativeBridge.request(request).also { if (request.getString("op") == "open") handle = it.getLong("handle") }
        }, mutations = repository.mutations)
        val directory = java.io.File(context.filesDir, "real-t3-lab-${System.nanoTime()}")
        suspend fun request(url: String, token: String, method: String = "GET", path: String = "/api/orchestration/shell"): JSONObject =
            NativeBridge.request(JSONObject().put("op", "labRequest").put("handle", handle)
                .put("url", url).put("token", token).put("method", method).put("path", path)
                .apply { if (method == "POST") put("body", JSONObject().put("type", "thread.archive").put("commandId", "denied-write").put("threadId", "thread-VQ")) })
        suspend fun denied(url: String, token: String, method: String = "GET", path: String = "/api/orchestration/shell") {
            val response = request(url, token, method, path)
            assertTrue("T3 must deny with 401/403", response.getInt("httpStatus") in listOf(401, 403))
        }
        var stage = "initial pairing"
        try {
            val client = controller.open(directory.absolutePath, directOnly = true)
            val invitation = configuration.getJSONObject("invitation")
            assertFalse(controller.pair(invitation))
            lab("/approve/$client")
            assertTrue(controller.pair(invitation))
            val strangerDirectory = java.io.File(context.filesDir, "stranger-${System.nanoTime()}")
            val stranger = NativeBridge.request(JSONObject().put("op", "open").put("stateDir", strangerDirectory.absolutePath).put("directOnly", true)).getLong("handle")
            try {
                var rejected = false
                try { NativeBridge.request(JSONObject().put("op", "pair").put("handle", stranger).put("invitation", invitation)) }
                catch (_: IllegalStateException) { rejected = true }
                assertTrue("Another client cannot claim the approved client's invitation", rejected)
            } finally { NativeBridge.request(JSONObject().put("op", "close").put("handle", stranger)); strangerDirectory.deleteRecursively() }
            controller.selectBridge(invitation.getJSONObject("address").getString("id"))
            stage = "initial catalog/enrollment"
            controller.refresh()
            val environments = repository.environments.first()
            assertEquals(2, environments.size)
            val rows = configuration.getJSONArray("environments")
            stage = "shells and denied requests"
            val sessions = (0 until rows.length()).map { index ->
                val expected = rows.getJSONObject(index)
                val row = environments.single { it.environmentId == expected.getString("id") }
                val origin = database.bridgeManagedDao().find(row.id)!!
                assertEquals("authorized", origin.status)
                assertTrue(origin.authorizationExpiresAt!! in (System.currentTimeMillis() + 1)..(System.currentTimeMillis() + 300_000))
                val token = repository.token(row.id)
                assertTrue("Real T3 issued a credential", token != null)
                val response = request(row.baseUrl, token!!)
                assertEquals(200, response.getInt("httpStatus"))
                val shell = response.getJSONObject("body")
                val threads = shell.getJSONArray("threads")
                assertTrue((0 until threads.length()).any { threads.getJSONObject(it).getString("id") == expected.getString("threadId") })
                val session = request(row.baseUrl, token, path = "/api/auth/session").getJSONObject("body")
                assertEquals("[\"orchestration:read\"]", session.getJSONArray("scopes").toString())
                if (row.environmentId == delegatedEnvironment) {
                    assertTrue("T3 subject must preserve the Android client, not the desktop caller",
                        session.getString("subject").endsWith(":$client"))
                    assertTrue("Delegated sessions must expire within two minutes",
                        origin.authorizationExpiresAt <= System.currentTimeMillis() + 120_000)
                }
                denied(row.baseUrl, token, "POST", "/api/auth/pairing-token")
                assertTrue("Real orchestration server enforces read scope", lab("/verify-write/$index", JSONObject().put("token", token)).getBoolean("writeDenied"))
                Triple(row.baseUrl, token, origin.authorizationExpiresAt)
            }
            assertTrue("The discovered catalog must contain the remotely issued environment",
                environments.any { it.environmentId == delegatedEnvironment })
            val delegation = lab("/delegation-status")
            assertEquals(issuerBridge, delegation.getString("issuerBridgeId"))
            assertEquals(delegatedEnvironment, delegation.getString("delegatedEnvironmentId"))
            assertTrue("At least one issuance must traverse the delegated handler",
                delegation.getLong("issuedCount") >= 1)
            denied(sessions[0].first, sessions[1].second)
            denied(sessions[1].first, sessions[0].second)
            // Natural expiry is enforced by T3 itself, independently of Android's gate.
            delay((sessions.maxOf { it.third } - System.currentTimeMillis() + 1200).coerceAtLeast(1))
            sessions.forEach { denied(it.first, it.second) }
            environments.forEach { assertNull(repository.token(it.id)) }
            stage = "renewal after natural expiry"
            controller.refresh()
            val renewed = environments.map { row -> row.baseUrl to checkNotNull(repository.token(row.id)) }
            lab("/revoke-sessions")
            renewed.forEach { denied(it.first, it.second) }
            // Explicit local adoption uses a NEW credential that the actual T3
            // server accepts, not a sentinel token or a metadata-only rename.
            val independent = environments.single { it.environmentId == rows.getJSONObject(0).getString("id") }
            stage = "new valid local credential"
            val fresh = NativeBridge.request(JSONObject().put("op", "enroll").put("handle", handle)
                .put("environmentId", independent.environmentId)).getString("accessToken")
            assertTrue(fresh != renewed.single { it.first == independent.baseUrl }.second)
            assertEquals(200, request(independent.baseUrl, fresh, path = "/api/auth/session").getInt("httpStatus"))
            repository.save(independent.id, "Explicit independent credential", independent.baseUrl, independent.environmentId, fresh)
            stage = "catalog after local adoption"
            controller.refresh()
            assertNull(database.bridgeManagedDao().find(independent.id))
            assertTrue(repository.token(independent.id) == fresh)
            stage = "bridge revocation with local ownership"
            lab("/revoke")
            controller.refresh()
            assertEquals("revoked", controller.status.value)
            environments.filterNot { it.id == independent.id }.forEach { assertNull(repository.token(it.id)) }
            assertNull(database.bridgeManagedDao().find(independent.id))
            assertTrue(repository.token(independent.id) == fresh)
            assertEquals(200, request(independent.baseUrl, fresh).getInt("httpStatus"))
        } catch (error: Exception) {
            throw AssertionError("Real T3 lab failed at $stage", error)
        } finally {
            controller.close(); scope.cancel(); database.close(); directory.deleteRecursively(); secrets.clear()
        }
    }
}
