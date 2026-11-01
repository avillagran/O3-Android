package dev.avillagran.ot3android.bridge

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.bridge.BridgeCatalogController
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ShellCursorStore
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.local.BridgeManagedEntity
import dev.avillagran.ot3android.core.data.local.EnvironmentEntity
import dev.avillagran.ot3android.core.data.local.NATIVE_AUTHORIZED
import dev.avillagran.ot3android.core.data.local.NATIVE_EXPIRED
import dev.avillagran.ot3android.core.data.local.Ot3Database
import dev.avillagran.ot3android.core.security.TokenStore
import dev.avillagran.ot3android.core.transport.NativeT3TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The controller against real Room transactions; only the native SDK is a fixture. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class AllHostsBridgeTest {
    private val provenance = "bridge:owner:client"
    private val now = 1_000_000L
    private val month = 30L * 24 * 60 * 60 * 1000
    private lateinit var database: Ot3Database
    private lateinit var scope: CoroutineScope
    private lateinit var repository: EnvironmentRepository
    private lateinit var connections: ConnectionManager
    private lateinit var controller: BridgeCatalogController
    private val secrets = mutableMapOf<String, String>()
    private val ops = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val stops = mutableListOf<String>()
    private var plan = plan()
    private var hostPair: () -> JSONObject = { receipt("write-session") }
    private var failWrite = false
    private var failDelete = false
    private var duringStop: suspend () -> Unit = {}
    private var sentLocal = JSONArray()
    private var status = "available"
    private var refresh: () -> JSONObject = { JSONObject().put("status", status) }
    private var hostRefresh: () -> JSONObject = { JSONObject().put("reachable", true).put("changed", false) }

    private val tokens = object : TokenStore {
        override suspend fun token(environmentId: String) = secrets[environmentId]
        override suspend fun setToken(environmentId: String, token: String) {
            if (failWrite) error("isolated-storage-failure")
            secrets[environmentId] = token
        }
        override suspend fun delete(environmentId: String) {
            if (failDelete) error("isolated-storage-failure")
            secrets.remove(environmentId)
        }
    }

    private fun receipt(token: String, expires: Long = now + month) = JSONObject().put("environmentId", "node")
        .put("provenance", provenance).put("accessToken", token).put("expiresAt", expires)
        .put("status", NATIVE_AUTHORIZED).put("baseUrl", "https://node.example/")

    private fun upsert(status: String, expires: Long?, baseUrl: String? = "https://node.example/") = JSONObject()
        .put("id", "row").put("environmentId", "node").put("label", "NODE")
        .put("baseUrl", baseUrl ?: JSONObject.NULL).put("provenance", provenance).put("status", status)
        .put("authorizationExpiresAt", expires ?: JSONObject.NULL)

    private fun plan(
        vararg upserts: JSONObject,
        stop: List<String> = emptyList(),
        enroll: List<String> = emptyList(),
        hostEnroll: List<String> = emptyList(),
        renew: List<String> = emptyList(),
    ) = JSONObject().put("upserts", JSONArray(upserts.toList())).put("stop", JSONArray(stop))
        .put("enroll", JSONArray(enroll)).put("hostEnroll", JSONArray(hostEnroll)).put("renew", JSONArray(renew))

    @Before fun setUp() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), Ot3Database::class.java)
            .allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cursors = object : ShellCursorStore {
            override suspend fun cursor(environmentId: String): Long? = null
            override suspend fun setCursor(environmentId: String, cursor: Long?) = Unit
        }
        repository = EnvironmentRepository(database.environmentDao(), { tokens }, cursors, clock = { now }, managed = database.bridgeManagedDao())
        connections = ConnectionManager(repository, cursors, NativeT3TransportFactory(), scope)
        connections.setForeground(false)
        controller = BridgeCatalogController(database, connections, scope, { tokens }, native = { request ->
            val op = request.getString("op")
            ops += op
            when (op) {
                "open" -> JSONObject().put("handle", 1).put("clientId", "client").put("bridgeId", JSONObject.NULL)
                "refresh" -> refresh()
                "cached" -> JSONObject().put("status", status)
                "projection" -> { sentLocal = request.getJSONArray("local"); plan }
                "enroll" -> receipt("read-session", now + 120_000).put("status", "authorized")
                "hostPair" -> hostPair()
                "hostRefresh" -> hostRefresh()
                else -> JSONObject()
            }
        }, clock = { now }, mutations = repository.mutations, stopConnection = {
            stops += it
            duringStop()
            connections.forget(it)
        })
        controller.open("/unused-native-fixture")
    }

    @After fun tearDown() = runBlocking {
        controller.close()
        scope.cancel()
        database.close()
    }

    private suspend fun seed(status: String, expires: Long?, secret: String? = "old-session") {
        database.environmentDao().upsert(EnvironmentEntity("row", "NODE", "https://node.example/", "node", 1, null, 0))
        database.bridgeManagedDao().upsert(BridgeManagedEntity("row", provenance, status, expires))
        secret?.let { secrets["row"] = it }
    }

    private suspend fun status() = database.bridgeManagedDao().find("row")

    @Test fun projectionReceivesStatusAndTheExpiryOfRowsHoldingASession() = runBlocking {
        for ((status, expires, sent) in listOf(
            Triple(NATIVE_AUTHORIZED, now + month, now + month),
            Triple(NATIVE_EXPIRED, now - 1, now - 1),
            Triple("authorized", now + 1, now + 1),
            Triple("credential_updating", now + month, null),
        )) {
            seed(status, expires)
            plan = plan()
            controller.refresh()
            val row = sentLocal.getJSONObject(0)
            assertEquals(status, row.getString("status"))
            assertEquals(sent, if (row.isNull("authorizationExpiresAt")) null else row.getLong("authorizationExpiresAt"))
        }
    }

    @Test fun anIndependentRowSendsNoOwnership() = runBlocking {
        database.environmentDao().upsert(EnvironmentEntity("mine", "Mine", "https://mine.example/", "node", 1, null, 0))
        plan = plan()
        controller.refresh()
        assertTrue(sentLocal.getJSONObject(0).isNull("status"))
        assertTrue(sentLocal.getJSONObject(0).isNull("provenance"))
    }

    @Test fun hostEnrollStoresAWriteSessionThroughHostPairOnly() = runBlocking {
        plan = plan(upsert("discovered", null), hostEnroll = listOf("node"))
        controller.refresh()
        assertFalse(ops.contains("enroll"))
        assertEquals(BridgeManagedEntity("row", provenance, NATIVE_AUTHORIZED, now + month), status())
        assertEquals("write-session", repository.token("row"))
        assertTrue("A connection follows the new session", "row" in connections.supervisors.value)
    }

    @Test fun anOrdinaryBridgeStaysOnTheReadOnlyPath() = runBlocking {
        plan = plan(upsert("discovered", null), enroll = listOf("node"))
        controller.refresh()
        assertFalse(ops.contains("hostPair"))
        assertEquals(BridgeManagedEntity("row", provenance, "authorized", now + 120_000), status())
        assertEquals("read-session", repository.token("row"))
    }

    @Test fun aPlanFromAnSdkWithoutAllHostsStillApplies() = runBlocking {
        plan = plan(upsert("discovered", null), enroll = listOf("node")).apply { remove("hostEnroll"); remove("renew") }
        controller.refresh()
        assertEquals("authorized", status()!!.status)
    }

    @Test fun aRefusedRenewalLeavesTheWorkingWriteTokenAlone() = runBlocking {
        seed(NATIVE_AUTHORIZED, now + 1000)
        plan = plan(upsert(NATIVE_AUTHORIZED, now + 1000), renew = listOf("node"))
        hostPair = { error("omabridge_host_pair_refused") }
        controller.refresh()
        assertEquals(BridgeManagedEntity("row", provenance, NATIVE_AUTHORIZED, now + 1000), status())
        assertEquals("old-session", repository.token("row"))
        assertEquals("Nothing was stopped for a renewal that never arrived", emptyList<String>(), stops)
        assertNotEquals("available_requires_authority", controller.status.value)
    }

    @Test fun aRenewalReplacesTheSessionAndExtendsIt() = runBlocking {
        seed(NATIVE_AUTHORIZED, now + 1000)
        plan = plan(upsert(NATIVE_AUTHORIZED, now + 1000), renew = listOf("node"))
        controller.refresh()
        assertEquals(now + month, status()!!.authorizationExpiresAt)
        assertEquals("write-session", repository.token("row"))
        assertTrue("row" in connections.supervisors.value)
    }

    @Test fun aRenewalThatCannotBeStoredKeepsTheRowUsable() = runBlocking {
        seed(NATIVE_AUTHORIZED, now + 1000)
        plan = plan(upsert(NATIVE_AUTHORIZED, now + 1000), renew = listOf("node"))
        failWrite = true
        controller.refresh()
        assertEquals(BridgeManagedEntity("row", provenance, NATIVE_AUTHORIZED, now + 1000), status())
        assertEquals("old-session", repository.token("row"))
        assertTrue("The stopped stream comes back", "row" in connections.supervisors.value)
    }

    @Test fun aFirstEnrollmentThatCannotBeStoredStaysDenied() = runBlocking {
        plan = plan(upsert("discovered", null), hostEnroll = listOf("node"))
        failWrite = true
        controller.refresh()
        assertEquals("credential_updating", status()!!.status)
        assertNull(repository.token("row"))
        assertEquals("available_requires_authority", controller.status.value)
    }

    @Test fun anExpiredNativeRowIsRenewedAndNeverReadOnly() = runBlocking {
        seed(NATIVE_EXPIRED, null)
        plan = plan(upsert(NATIVE_EXPIRED, null), stop = listOf("row"), renew = listOf("node"))
        controller.refresh()
        assertFalse(ops.contains("enroll"))
        assertEquals(NATIVE_AUTHORIZED, status()!!.status)
    }

    @Test fun aReadOnlyReceiptCannotReplaceAWriteToken() = runBlocking {
        seed(NATIVE_AUTHORIZED, now + month)
        controller.enroll("node")
        assertEquals(BridgeManagedEntity("row", provenance, NATIVE_AUTHORIZED, now + month), status())
        assertEquals("old-session", repository.token("row"))
    }

    @Test fun anExplicitLocalCredentialWinsOverARenewal() = runBlocking {
        seed(NATIVE_AUTHORIZED, now + 1000)
        plan = plan(upsert(NATIVE_AUTHORIZED, now + 1000), renew = listOf("node"))
        duringStop = { repository.save("row", "Local", "https://local.example/", "node", "local-session") }
        controller.refresh()
        assertNull(status())
        assertEquals("local-session", repository.token("row"))
        assertEquals("https://local.example/", repository.find("row")!!.baseUrl)
    }

    @Test fun withdrawalStopsAnyLateConnectionAndDeletesTheCredential() = runBlocking {
        seed(NATIVE_AUTHORIZED, now + month)
        plan = plan(upsert("revoked", null, baseUrl = null), stop = listOf("row"))
        controller.refresh()
        assertEquals("revoked", status()!!.status)
        assertNull(secrets["row"])
        assertEquals("Stopped by the plan and again after the gate closed", listOf("row", "row"), stops)
        assertFalse("row" in connections.supervisors.value)
    }

    @Test fun aFailedDeleteStaysDeniedAndTheNextRefreshRetries() = runBlocking {
        seed(NATIVE_AUTHORIZED, now + month)
        plan = plan(upsert("revoked", null, baseUrl = null), stop = listOf("row"))
        failDelete = true
        controller.refresh()
        assertEquals("old-session", secrets["row"])
        assertNull(repository.token("row"))
        failDelete = false
        controller.refresh()
        assertNull(secrets["row"])
    }

    @Test fun withdrawalDoesNotDeleteALocalCredentialSavedDuringShutdown() = runBlocking {
        seed(NATIVE_AUTHORIZED, now + month)
        plan = plan(upsert("revoked", null, baseUrl = null), stop = listOf("row"))
        duringStop = { repository.save("row", "Local", "https://local.example/", "node", "local-session") }
        controller.refresh()
        assertNull(status())
        assertEquals("local-session", repository.token("row"))
    }

    @Test fun aStaleCatalogLeavesANativeRowRunning() = runBlocking {
        seed(NATIVE_AUTHORIZED, now + month)
        plan = plan(upsert(NATIVE_AUTHORIZED, now + month))
        controller.refresh()
        assertEquals(emptyList<String>(), stops)
        assertEquals("old-session", repository.token("row"))
    }

    @Test fun aNativeRowComesBackAfterARouteChange() = runBlocking {
        seed(NATIVE_AUTHORIZED, now + month)
        plan = plan(upsert(NATIVE_AUTHORIZED, now + month, baseUrl = "https://moved.example/"), stop = listOf("row"))
        controller.refresh()
        assertEquals("https://moved.example/", repository.find("row")!!.baseUrl)
        assertTrue("row" in connections.supervisors.value)
    }

    /** Polls until [op] has run, as the foreground loop does. */
    private suspend fun awaitOp(op: String) {
        repeat(500) {
            if (op in ops) return
            kotlinx.coroutines.delay(10)
        }
        fail("$op did not run: $ops")
    }

    private suspend fun selectBridge() {
        controller.selectBridge("a".repeat(64))
    }

    @Test fun theForegroundLoopRefreshesTheBridgeAddressAfterTheCatalog() = runBlocking {
        selectBridge()
        controller.setForeground(true)
        awaitOp("hostRefresh")
        controller.setForeground(false)
        assertTrue(ops.indexOf("projection") < ops.indexOf("hostRefresh"))
    }

    @Test fun aFailedAddressRefreshSaysNothingAboutTheCatalog() = runBlocking {
        hostRefresh = { error("isolated-native-failure") }
        selectBridge()
        controller.setForeground(true)
        awaitOp("hostRefresh")
        controller.setForeground(false)
        assertEquals("available", controller.status.value)
    }

    @Test fun aWithdrawnBridgeIsNotAskedForItsAddress() = runBlocking {
        plan = plan()
        selectBridge()
        status = "revoked"
        controller.setForeground(true)
        awaitOp("projection")
        kotlinx.coroutines.delay(100)
        controller.setForeground(false)
        assertFalse("hostRefresh" in ops)
    }

    private val offer get() = plan().put("routeFallbacks", JSONArray().put(JSONObject().put("id", "lan").put("baseUrl", "https://node.example/")))

    private fun usableRoutes(): Map<String, String> = connections.approvedRouteFallbacks.value

    @Test fun aNetworkChangeBurstAsksForTheAddressOnceAndTheIconAsksAgain() = runBlocking {
        selectBridge()
        controller.setForeground(true)
        awaitOp("hostRefresh")
        repeat(5) {
            ops.clear()
            controller.reconnect()
            awaitOp("projection")
        }
        kotlinx.coroutines.delay(100)
        assertFalse("hostRefresh" in ops)

        ops.clear()
        controller.refreshNow()
        awaitOp("hostRefresh")
        controller.setForeground(false)
    }

    @Test fun aRejectedCatalogClosesTheRoutesUntilOneValidates() = runBlocking {
        repository.save("lan", "NODE", "http://192.168.1.177:3773", "node", "secret")
        connections.approveRoute("lan", "https://node.example:443")
        plan = offer
        controller.refresh()
        assertEquals(mapOf("lan" to "https://node.example/"), usableRoutes())

        refresh = { error("bridge_catalog_revision_reused") }
        assertTrue(runCatching { controller.refresh() }.isFailure)
        assertEquals("nothing old stays live", emptyMap<String, String>(), usableRoutes())

        // The cache a bridge left behind does not bring them back when it goes silent.
        refresh = { JSONObject().put("status", "offline") }
        controller.refresh()
        assertEquals(emptyMap<String, String>(), usableRoutes())

        refresh = { JSONObject().put("status", "available") }
        controller.refresh()
        assertEquals(mapOf("lan" to "https://node.example/"), usableRoutes())
    }

    @Test fun anExpiredCatalogTakesItsOfferAway() = runBlocking {
        repository.save("lan", "NODE", "http://192.168.1.177:3773", "node", "secret")
        connections.approveRoute("lan", "https://node.example:443")
        plan = offer
        controller.refresh()
        assertEquals(1, usableRoutes().size)

        // Past the SDK's limit the plan stops offering it.
        status = "expired"
        plan = plan()
        controller.refresh()
        assertEquals(emptyMap<String, String>(), usableRoutes())
    }

    @Test fun anOfferAloneOpensNothing() = runBlocking {
        repository.save("lan", "NODE", "http://192.168.1.177:3773", "node", "secret")
        plan = offer
        controller.refresh()
        assertEquals(emptyMap<String, String>(), usableRoutes())
    }

    @Test fun aRowOfThePlanGetsItsFallbackAndKeepsItsUrl() = runBlocking {
        repository.save("lan", "NODE", "http://192.168.1.177:3773", "node", "secret")
        plan = offer
        controller.refresh()
        assertEquals("http://192.168.1.177:3773", repository.find("lan")!!.baseUrl)
        assertNull("independent: nothing the bridge owns", database.bridgeManagedDao().find("lan"))
        assertEquals("secret", repository.token("lan"))
    }
}
