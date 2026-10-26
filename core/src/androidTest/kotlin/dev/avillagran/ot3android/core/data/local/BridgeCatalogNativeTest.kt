package dev.avillagran.ot3android.core.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.core.bridge.BridgeCatalogController
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ShellCursorStore
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.security.TokenStore
import dev.avillagran.ot3android.core.transport.NativeT3TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in, real JNI + QUIC + Room test against the isolated loopback Rust example. */
class BridgeCatalogNativeTest {
    @Test
    fun pairedBridgeProjectsTwoHostsWithoutTouchingLocalDraftsOrCursors() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val port = InstrumentationRegistry.getArguments().getString("catalogLabPort")
        assumeTrue("Start the isolated catalog_mobile_lab and pass catalogLabPort", port != null)
        val context = instrument.targetContext
        val database = Room.inMemoryDatabaseBuilder(context, Ot3Database::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secrets = mutableMapOf<String, String>()
        val tokens = object : TokenStore {
            override suspend fun token(environmentId: String) = secrets[environmentId]
            override suspend fun setToken(environmentId: String, token: String) { secrets[environmentId] = token }
            override suspend fun delete(environmentId: String) { secrets.remove(environmentId) }
        }
        val cursorValues = mutableMapOf<String, Long?>()
        val cursors = object : ShellCursorStore {
            override suspend fun cursor(environmentId: String) = cursorValues[environmentId]
            override suspend fun setCursor(environmentId: String, cursor: Long?) { cursorValues[environmentId] = cursor }
        }
        val repository = EnvironmentRepository(database.environmentDao(), { tokens }, cursors, managed = database.bridgeManagedDao())
        val connections = ConnectionManager(repository, cursors, NativeT3TransportFactory(), scope)
        val controller = BridgeCatalogController(database, connections, scope, { tokens }, mutations = repository.mutations, native = { request ->
            try { dev.avillagran.ot3android.core.bridge.NativeBridge.request(request) }
            catch (error: Exception) { throw IllegalStateException("Native operation ${request.getString("op")}", error) }
        })
        val directory = java.io.File(context.filesDir, "catalog-lab-${System.nanoTime()}")
        suspend fun lab(path: String): JSONObject = withContext(Dispatchers.IO) {
            val connection = URL("http://10.0.2.2:$port$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            try { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) } finally { connection.disconnect() }
        }
        var stage = "local rows"
        try {
            val independent = repository.save(null, "Independent", "https://independent.invalid/", "independent-uuid", "private-fixture-token")
            cursors.setCursor(independent.id, 42)
            database.draftDao().upsert(DraftEntity("${independent.id}/thread", "unfinished", 1))
            stage = "native open"
            val client = controller.open(directory.absolutePath, directOnly = true)
            stage = "invitation"
            val invitation = lab("/invitation")
            stage = "native pair pending"
            assertFalse(controller.pair(invitation))
            stage = "lab approve"
            lab("/approve/$client")
            stage = "native pair approved"
            assertTrue(controller.pair(invitation))
            stage = "select bridge"
            controller.selectBridge(invitation.getJSONObject("address").getString("id"))
            stage = "native catalog refresh"
            controller.refresh()
            val initial = repository.environments.first()
            assertEquals(3, initial.size)
            val node = initial.single { it.environmentId == NODE }
            val vq = initial.single { it.environmentId == VQ }
            cursors.setCursor(node.id, 7)
            database.draftDao().upsert(DraftEntity("${node.id}/thread", "node draft", 2))
            assertNull(repository.token(node.id))
            stage = "catalog rename/recovery"
            lab("/change"); controller.refresh()
            val changed = repository.find(node.id)!!
            assertEquals("NODE renamed", changed.label)
            assertEquals("https://changed-node.fixture.invalid/", changed.baseUrl)
            assertEquals(7L, cursors.cursor(node.id))
            stage = "catalog withdrawal"
            lab("/withdraw"); controller.refresh()
            assertEquals("revoked", database.bridgeManagedDao().find(node.id)!!.status)
            assertEquals("", repository.find(node.id)!!.baseUrl)
            assertEquals(3, repository.environments.first().size)
            assertEquals(vq.id, repository.environments.first().single { it.environmentId == VQ }.id)
            stage = "catalog rename/recovery"
            lab("/change"); controller.refresh()
            assertEquals("discovered", database.bridgeManagedDao().find(node.id)!!.status)
            assertEquals(7L, cursors.cursor(node.id))
            stage = "native process restart"
            controller.close()
            assertEquals(client, controller.open(directory.absolutePath, directOnly = true))
            controller.refresh() // Restores the selected bridge; no selectBridge/pair call.
            assertEquals(3, repository.environments.first().size)
            assertEquals(node.id, repository.environments.first().single { it.environmentId == NODE }.id)
            stage = "native grant revoke"
            lab("/revoke"); controller.refresh()
            assertEquals("revoked", controller.status.value)
            assertTrue(database.bridgeManagedDao().all().all { it.status == "revoked" })
            assertEquals("private-fixture-token", repository.token(independent.id))
            assertEquals(42L, cursors.cursor(independent.id))
            assertEquals("node draft", database.draftDao().find("${node.id}/thread")?.text)
        } catch (error: Exception) {
            throw AssertionError("Catalog lab failed at $stage", error)
        } finally {
            controller.close(); scope.cancel(); database.close(); directory.deleteRecursively()
        }
    }
    private companion object {
        const val VQ = "00000000-0000-4000-8000-000000000001"
        const val NODE = "00000000-0000-4000-8000-000000000002"
    }
}
