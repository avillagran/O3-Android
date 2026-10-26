package dev.avillagran.ot3android.core.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.core.bridge.BridgeCatalogController
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ShellCursorStore
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.security.TokenStore
import dev.avillagran.ot3android.core.transport.NativeT3TransportFactory
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeOwnershipRaceTest {
    @Test fun withdrawalSurvivesConnectionTimestampButLocalCredentialAdoptionWins() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, Ot3Database::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secrets = mutableMapOf("node" to "old-bridge-credential")
        val tokens = object : TokenStore {
            override suspend fun token(environmentId: String) = secrets[environmentId]
            override suspend fun setToken(environmentId: String, token: String) { secrets[environmentId] = token }
            override suspend fun delete(environmentId: String) { secrets.remove(environmentId) }
        }
        val cursors = object : ShellCursorStore {
            override suspend fun cursor(environmentId: String): Long? = 7
            override suspend fun setCursor(environmentId: String, cursor: Long?) = Unit
        }
        val repository = EnvironmentRepository(database.environmentDao(), { tokens }, cursors, managed = database.bridgeManagedDao())
        val connections = ConnectionManager(repository, cursors, NativeT3TransportFactory(), scope)
        var adopt = false
        val controller = BridgeCatalogController(database, connections, scope, { tokens }, native = { request ->
            when (request.getString("op")) {
                "open" -> JSONObject().put("handle", 1).put("clientId", "client").put("bridgeId", JSONObject.NULL)
                "refresh" -> JSONObject().put("status", "revoked")
                "projection" -> {
                    // The native projection was calculated from the earlier snapshot.
                    // A connection close/local save now writes before the plan commits.
                    if (adopt) repository.save("node", "Independent", "https://new.example/", "node", "valid-new-local-session")
                    else repository.markConnected("node", "node")
                    JSONObject().put("stop", JSONArray().put("node")).put("enroll", JSONArray())
                        .put("upserts", JSONArray().put(JSONObject().put("id", "node").put("label", "Withdrawn")
                            .put("baseUrl", JSONObject.NULL).put("environmentId", "node").put("provenance", "bridge:owner:client")
                            .put("status", "revoked").put("authorizationExpiresAt", JSONObject.NULL)))
                }
                else -> JSONObject()
            }
        }, mutations = repository.mutations)
        suspend fun seed() {
            database.environmentDao().upsert(EnvironmentEntity("node", "NODE", "https://node.example/", "node", 1, null, 0))
            database.bridgeManagedDao().upsert(BridgeManagedEntity("node", "bridge:owner:client", "authorized", System.currentTimeMillis() + 120_000))
        }
        try {
            controller.open("/unused-native-fixture")
            seed(); controller.refresh()
            assertEquals("revoked", database.bridgeManagedDao().find("node")!!.status)
            assertEquals("", repository.find("node")!!.baseUrl)
            assertNotNull(repository.find("node")!!.lastConnectedAt)
            assertNull(repository.token("node"))
            seed(); adopt = true; controller.refresh()
            assertNull(database.bridgeManagedDao().find("node"))
            assertEquals("Independent", repository.find("node")!!.label)
            assertEquals("https://new.example/", repository.find("node")!!.baseUrl)
            assertTrue(repository.token("node") == "valid-new-local-session")
        } finally { controller.close(); scope.cancel(); database.close() }
    }

    @Test fun localAdoptionDuringEnrollmentShutdownCannotBeOverwritten() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, Ot3Database::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var secret = "old-session"
        val tokens = object : TokenStore {
            override suspend fun token(environmentId: String) = secret
            override suspend fun setToken(environmentId: String, token: String) { secret = token }
            override suspend fun delete(environmentId: String) = Unit
        }
        val cursors = object : ShellCursorStore {
            override suspend fun cursor(environmentId: String): Long? = null
            override suspend fun setCursor(environmentId: String, cursor: Long?) = Unit
        }
        val repository = EnvironmentRepository(database.environmentDao(), { tokens }, cursors, managed = database.bridgeManagedDao())
        val connections = ConnectionManager(repository, cursors, NativeT3TransportFactory(), scope)
        database.environmentDao().upsert(EnvironmentEntity("node", "NODE", "https://node.example/", "node", 1, null, 0))
        database.bridgeManagedDao().upsert(BridgeManagedEntity("node", "bridge:owner:client", "discovered", null))
        val controller = BridgeCatalogController(database, connections, scope, { tokens }, native = { request ->
            when (request.getString("op")) {
                "open" -> JSONObject().put("handle", 1).put("clientId", "client").put("bridgeId", JSONObject.NULL)
                "enroll" -> JSONObject().put("environmentId", "node").put("provenance", "bridge:owner:client")
                    .put("accessToken", "new-bridge-session").put("expiresAt", System.currentTimeMillis() + 120_000)
                else -> JSONObject()
            }
        }, mutations = repository.mutations, stopConnection = {
            // The bridge has a receipt and read its owner. Stream shutdown yields
            // to an explicitly paired independent credential before installation.
            repository.save("node", "Local", "https://local.example/", "node", "valid-local-session")
        })
        try {
            controller.open("/unused-native-fixture")
            controller.enroll("node")
            assertNull(database.bridgeManagedDao().find("node"))
            assertEquals("https://local.example/", repository.find("node")!!.baseUrl)
            assertTrue(repository.token("node") == "valid-local-session")
        } finally { controller.close(); scope.cancel(); database.close() }
    }

    @Test fun interruptedCredentialReplacementAndFailedDeleteStayDeniedAcrossRoomReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "bridge-failure-${System.nanoTime()}.db"
        var database = Room.databaseBuilder(context, Ot3Database::class.java, name).build()
        var secret = "old-bridge-session"
        var failWrite = true
        val tokens = object : TokenStore {
            override suspend fun token(environmentId: String) = secret
            override suspend fun setToken(environmentId: String, token: String) { secret = token; if (failWrite) error("storage-failure-after-write") }
            override suspend fun delete(environmentId: String) { error("storage-failure-before-delete") }
        }
        val cursors = object : ShellCursorStore {
            override suspend fun cursor(environmentId: String): Long? = 7
            override suspend fun setCursor(environmentId: String, cursor: Long?) = Unit
        }
        fun repository() = EnvironmentRepository(database.environmentDao(), { tokens }, cursors, managed = database.bridgeManagedDao())
        try {
            database.environmentDao().upsert(EnvironmentEntity("node", "NODE", "https://node.example/", "node", 1, null, 0))
            database.bridgeManagedDao().upsert(BridgeManagedEntity("node", "bridge:owner:client", "authorized", System.currentTimeMillis() + 120_000))
            try { repository().save("node", "Local", "https://new.example/", "node", "valid-new-local-session"); fail("Expected write failure") } catch (_: IllegalStateException) { }
            database.close(); database = Room.databaseBuilder(context, Ot3Database::class.java, name).build()
            assertNull(repository().token("node"))
            assertEquals("https://node.example/", repository().find("node")!!.baseUrl)
            failWrite = false
            repository().save("node", "Local", "https://new.example/", "node", "valid-new-local-session")
            assertTrue(repository().token("node") == "valid-new-local-session")
            database.bridgeManagedDao().upsert(BridgeManagedEntity("node", "bridge:owner:client", "revoked", null))
            repository().save("node", "Renamed", "https://new.example/", "node", null)
            assertNull(repository().token("node"))
            database.bridgeManagedDao().upsert(BridgeManagedEntity("node", "bridge:owner:client", "authorized", System.currentTimeMillis() + 120_000))
            try { repository().delete("node"); fail("Expected delete failure") } catch (_: IllegalStateException) { }
            database.close(); database = Room.databaseBuilder(context, Ot3Database::class.java, name).build()
            assertNull(repository().token("node"))
            assertNotNull(database.bridgeManagedDao().find("node"))
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
