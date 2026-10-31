package dev.avillagran.ot3android.ui.pair

import dev.avillagran.ot3android.core.connection.ShellCursorStore
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.local.EnvironmentDao
import dev.avillagran.ot3android.core.data.local.EnvironmentEntity
import dev.avillagran.ot3android.core.security.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

import androidx.compose.ui.test.*
import android.app.Application
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.pairing.PairingService
import dev.avillagran.ot3android.core.transport.FakeT3TransportFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class BridgePairViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val peer = "a".repeat(64)
    private var calls = 0
    private var answer: suspend () -> Boolean = { false }
    private fun model(supported: Boolean = true) = PairViewModel(
        PairingService(FakeT3TransportFactory(), fakeRepository(), dev.avillagran.ot3android.core.transport.PairingClient(), {}, {}),
        { calls++; answer() }, { supported },
    )
    private fun link(manage: Boolean = false, expires: Long = 4102444800) = "omabridge://pair/" +
        Base64.encodeToString("""{"version":1,"address":{"id":"$peer"},"name":"Fixture","token":"fixture-only","expires":$expires,"manage":$manage}""".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun scanningOnlyReviewsAndFalseConsentNeverPairs() = runTest(dispatcher) {
        val model = model()
        model.open(link())
        runCurrent()
        assertEquals("review", model.state.value.bridge)
        assertEquals(peer, model.state.value.bridgeTarget?.peerId)
        model.confirmBridge(peer, false)
        runCurrent()
        assertEquals(0, calls)
        model.dismissBridge()
    }

    @Test fun pendingApprovalRemainsOnTheAuthorizedHostAndDropsTheSecret() = runTest(dispatcher) {
        val model = model()
        model.open(link()); runCurrent()
        model.confirmBridge(peer, true); runCurrent()
        assertEquals(1, calls)
        assertEquals("pending", model.state.value.bridge)
        assertEquals("", model.state.value.link)
        assertNull(model.state.value.bridgeTarget)
        model.inspect(); runCurrent()
        assertEquals("No implicit approval or replay", 1, calls)
        model.dismissBridge()
    }

    @Test fun dismissalBeforeTheQueuedRequestPreventsSendingInvitation() = runTest(dispatcher) {
        val model = model()
        model.open(link()); runCurrent()
        model.confirmBridge(peer, true)
        model.dismissBridge()
        runCurrent()
        assertEquals(0, calls)
        assertEquals("", model.state.value.link)
        assertNull(model.state.value.bridge)
    }

    @Test fun dismissalCancelsPendingPairAndNeverShowsItsLateResult() = runTest(dispatcher) {
        val reply = CompletableDeferred<Boolean>()
        answer = { reply.await() }
        val model = model()
        model.open(link()); runCurrent()
        model.confirmBridge(peer, true); runCurrent()
        assertEquals(1, calls)
        model.dismissBridge()
        reply.complete(true)
        runCurrent()
        assertNull(model.state.value.bridge)
        assertEquals("", model.state.value.link)
    }

    @Test fun unsupportedManagementAndExpiredInvitationsNeverSend() = runTest(dispatcher) {
        val model = model()
        for (link in listOf(link(manage = true), link(expires = 1))) {
            model.open(link); runCurrent()
            assertEquals("failed", model.state.value.bridge)
            assertEquals("", model.state.value.link)
        }
        assertEquals(0, calls)
        model.dismissBridge()
    }

    @Test fun expiryLabelsChangeOfflineWithoutANewCatalogEmission() = runTest(dispatcher) {
        var now = 100L
        val dao = FakeEnvironmentDao()
        dao.upsert(EnvironmentEntity("row", "Fixture", "https://fixture.example", "env", 0, null, 0))
        val rows = MutableStateFlow(listOf(dev.avillagran.ot3android.core.data.local.BridgeManagedEntity(
            "row", "bridge:owner:client", dev.avillagran.ot3android.core.data.local.NATIVE_AUTHORIZED, 200,
        )))
        val managed = object : dev.avillagran.ot3android.core.data.local.BridgeManagedDao {
            override fun observeAll() = rows
            override suspend fun all() = rows.value
            override suspend fun find(id: String) = rows.value.find { it.id == id }
            override suspend fun upsert(row: dev.avillagran.ot3android.core.data.local.BridgeManagedEntity) { rows.value = listOf(row) }
            override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
        }
        val repository = EnvironmentRepository(dao, { FakeTokenStore() }, FakeCursorStore(), clock = { now }, managed = managed)
        val connections = dev.avillagran.ot3android.core.connection.ConnectionManager(
            repository, FakeCursorStore(), FakeT3TransportFactory(), backgroundScope,
        )
        val model = dev.avillagran.ot3android.ui.environments.EnvironmentsViewModel(repository, connections) { now }
        val store = androidx.lifecycle.ViewModelStore().also { it.put("expiry", model) }
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.items.collect {} }
        try {
            runCurrent()
            assertEquals(dev.avillagran.ot3android.core.data.local.NATIVE_AUTHORIZED, model.items.value?.single()?.bridgeStatus)
            now = 200
            advanceTimeBy(1_000); runCurrent()
            assertEquals(dev.avillagran.ot3android.core.data.local.NATIVE_EXPIRED, model.items.value?.single()?.bridgeStatus)
        } finally { collector.cancel(); store.clear() }
    }

    @Test fun deviceIdentityUsesSdkIdsAndSelectionRejectsPeerPrefixes() = runTest(dispatcher) {
        val database = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            dev.avillagran.ot3android.core.data.local.Ot3Database::class.java,
        ).allowMainThreadQueries().build()
        val operations = mutableListOf<String>()
        val connections = dev.avillagran.ot3android.core.connection.ConnectionManager(
            fakeRepository(), FakeCursorStore(), FakeT3TransportFactory(), backgroundScope,
        )
        val controller = dev.avillagran.ot3android.core.bridge.BridgeCatalogController(
            database, connections, backgroundScope, { FakeTokenStore() },
            native = { request ->
                val op = request.getString("op")
                operations += op
                when (op) {
                    "open" -> org.json.JSONObject().put("handle", 1).put("clientId", "b".repeat(64)).put("bridgeId", org.json.JSONObject.NULL)
                    "cached" -> org.json.JSONObject().put("status", "available")
                    else -> org.json.JSONObject()
                }
            }, mutations = kotlinx.coroutines.sync.Mutex(),
        )
        try {
            controller.open("/fixture")
            assertEquals("b".repeat(64), controller.identity.value?.clientId)
            assertNull(controller.identity.value?.bridgeId)
            try { controller.selectBridge(peer.take(8)); fail("prefix") } catch (_: IllegalArgumentException) { }
            assertEquals(listOf("open"), operations)
            controller.selectBridge(peer)
            assertEquals(peer, controller.identity.value?.bridgeId)
            controller.close()
            assertNull(controller.identity.value)
        } finally { database.close() }
    }

    @Test fun absentSdkDoesNotTryPairing() = runTest(dispatcher) {
        val model = model(false)
        model.open(link()); runCurrent()
        assertEquals("sdk_unavailable", model.state.value.bridge)
        assertEquals(0, calls)
        model.dismissBridge()
    }
}

private class FakeEnvironmentDao : EnvironmentDao {
    val rows = MutableStateFlow<Map<String, EnvironmentEntity>>(emptyMap())
    override fun observeAll(): Flow<List<EnvironmentEntity>> = rows.map { it.values.sortedBy { e -> e.sortOrder } }
    override suspend fun findById(id: String) = rows.value[id]
    override suspend fun upsert(environment: EnvironmentEntity) = rows.update { it + (environment.id to environment) }
    override suspend fun deleteById(id: String) = rows.update { it - id }
    override suspend fun markConnected(id: String, environmentId: String?, connectedAt: Long) = rows.update { current ->
        val row = current[id] ?: return@update current
        current + (id to row.copy(environmentId = environmentId ?: row.environmentId, lastConnectedAt = connectedAt))
    }
}

private class FakeTokenStore : TokenStore {
    val tokens = mutableMapOf<String, String>()
    override suspend fun token(environmentId: String) = tokens[environmentId]
    override suspend fun setToken(environmentId: String, token: String) { tokens[environmentId] = token }
    override suspend fun delete(environmentId: String) { tokens.remove(environmentId) }
}

private class FakeCursorStore : ShellCursorStore {
    val cursors = mutableMapOf<String, Long>()
    override suspend fun cursor(environmentId: String) = cursors[environmentId]
    override suspend fun setCursor(environmentId: String, cursor: Long?) {
        if (cursor == null) cursors.remove(environmentId) else cursors[environmentId] = cursor
    }
}

private fun fakeRepository(
    dao: FakeEnvironmentDao = FakeEnvironmentDao(),
    tokens: FakeTokenStore = FakeTokenStore(),
) = EnvironmentRepository(dao, { tokens }, FakeCursorStore())

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BridgePairReviewScreenTest {
    @Before fun setupMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }
    @get:org.junit.Rule val rule = androidx.compose.ui.test.junit4.v2.createComposeRule()

    @Test fun allHostsWarningAndExactIdentityPrecedeConsentAndNetwork() {
        var calls = 0
        val model = PairViewModel(
            PairingService(FakeT3TransportFactory(), fakeRepository(), dev.avillagran.ot3android.core.transport.PairingClient(), {}, {}),
            { calls++; false }, { true },
        )
        val inbox = PairingInbox()
        rule.setContent { dev.avillagran.ot3android.ui.theme.Ot3Theme {
            PairScreen(model, inbox.link, inbox::take, {}, {}, {})
        } }
        val peer = "a".repeat(64)
        val link = "omabridge://pair/" + Base64.encodeToString(
            """{"version":1,"address":{"id":"$peer"},"token":"fixture-only","expires":4102444800,"manage":false}""".toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        rule.runOnIdle { model.open(link) }
        rule.waitUntil { model.state.value.bridge == "review" }
        rule.onNodeWithTag("bridge_peer_id").assertTextEquals(peer)
        rule.onNodeWithText("This invitation may allow access to all hosts. Review its scope with the issuer before continuing.").assertExists()
        rule.onNodeWithTag("bridge_peer_pair").assertIsNotEnabled()
        rule.onNodeWithTag("bridge_peer_consent").assertIsOff()
        assertEquals(0, calls)
        rule.onNodeWithTag("bridge_peer_consent").performScrollTo().performClick()
        rule.onNodeWithTag("bridge_peer_pair").performScrollTo().performClick()
        rule.waitUntil { model.state.value.bridge == "pending" }
        assertEquals(1, calls)
        rule.onNodeWithText("Approval stays on the authorized host. After approval, import the invitation again.").assertExists()
    }
}
