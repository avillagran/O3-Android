package dev.avillagran.ot3android.ui.pair

import dev.avillagran.ot3android.core.connection.ShellCursorStore
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.local.EnvironmentDao
import dev.avillagran.ot3android.core.data.local.EnvironmentEntity
import dev.avillagran.ot3android.core.pairing.PairingFailure
import dev.avillagran.ot3android.core.pairing.PairingService
import dev.avillagran.ot3android.core.pairing.PairingState
import dev.avillagran.ot3android.core.security.TokenStore
import dev.avillagran.ot3android.testing.assumeHostJni
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.FakeT3TransportFactory
import dev.avillagran.ot3android.core.transport.PairedBearer
import dev.avillagran.ot3android.core.transport.PairingClient
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private var pairAnswer: suspend () -> PairedBearer = { paired() }
    private val factory = FakeT3TransportFactory {
        FakeT3Transport().apply {
            onFetchDescriptor(ORIGIN) { descriptor() }
            onPairBearer(ORIGIN) { pairAnswer() }
        }
    }
    private val repository = EnvironmentRepository(MemoryDao(), { MemoryTokens() }, NoCursors)
    private val viewModel by lazy {
        PairViewModel(
            PairingService(factory, repository, PairingClient(label = "ot3 test"), {}, {}, io = dispatcher, domain = DomainEngine(dispatcher)),
        )
    }

    @Before
    fun setUp() {
        // Links are read by the lib's `connectionCatalog.pairingTarget` (host JNI library).
        assumeHostJni()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aLinkFromOutsideOnlyFillsTheFieldAndShowsItsHost() {
        viewModel.setLink(LINK)

        assertEquals(LINK, viewModel.state.value.link)
        assertEquals(ORIGIN, viewModel.state.value.linkOrigin)
        assertEquals(PairingState.Idle, viewModel.state.value.pairing)
        assertTrue("nothing sent before Continue", factory.created.isEmpty())

        viewModel.inspect()

        assertTrue(viewModel.state.value.pairing is PairingState.Ready)
    }

    @Test
    fun aScannedLinkIsReadAtOnce() {
        viewModel.open(LINK)

        assertTrue(viewModel.state.value.pairing is PairingState.Ready)
    }

    @Test
    fun aRefusedCodeIsNotKept() {
        pairAnswer = { throw T3Exception("pairing_rejected") }
        viewModel.open(LINK)

        viewModel.confirm()

        assertEquals(PairingFailure.Rejected, (viewModel.state.value.pairing as PairingState.Failed).failure)
        assertEquals("", viewModel.state.value.link)
    }

    @Test
    fun aMalformedCodeIsNotKept() {
        viewModel.open("$ORIGIN/pair#token=%01")

        assertEquals(PairingFailure.InvalidCode, (viewModel.state.value.pairing as PairingState.Failed).failure)
        assertEquals("", viewModel.state.value.link)
        assertNull(viewModel.state.value.linkOrigin)
    }

    @Test
    fun anUnreachableServerKeepsTheLinkToTryAgain() {
        pairAnswer = { throw T3Exception("unreachable") }
        viewModel.open(LINK)

        viewModel.confirm()

        assertEquals(PairingFailure.Unreachable, (viewModel.state.value.pairing as PairingState.Failed).failure)
        assertEquals(LINK, viewModel.state.value.link)
        assertFalse(viewModel.state.value.toString().contains(CODE))
    }

    private fun descriptor() = JSONObject().put("environmentId", ENVIRONMENT).put("label", "node")

    private fun paired() = PairedBearer(
        accessToken = "long-lived-access-token",
        tokenType = "Bearer",
        environmentId = ENVIRONMENT,
        descriptor = descriptor(),
        scope = "orchestration:read",
        expiresIn = null,
        connected = false,
    )

    private class MemoryDao : EnvironmentDao {
        private val rows = MutableStateFlow<Map<String, EnvironmentEntity>>(emptyMap())
        override fun observeAll(): Flow<List<EnvironmentEntity>> = rows.map { it.values.toList() }
        override suspend fun findById(id: String) = rows.value[id]
        override suspend fun upsert(environment: EnvironmentEntity) = rows.update { it + (environment.id to environment) }
        override suspend fun markConnected(id: String, environmentId: String?, connectedAt: Long) = Unit
        override suspend fun deleteById(id: String) = rows.update { it - id }
    }

    private class MemoryTokens : TokenStore {
        private val tokens = mutableMapOf<String, String>()
        override suspend fun token(environmentId: String) = tokens[environmentId]
        override suspend fun setToken(environmentId: String, token: String) { tokens[environmentId] = token }
        override suspend fun delete(environmentId: String) { tokens.remove(environmentId) }
    }

    private object NoCursors : ShellCursorStore {
        override suspend fun cursor(environmentId: String): Long? = null
        override suspend fun setCursor(environmentId: String, cursor: Long?) = Unit
    }

    private companion object {
        const val ORIGIN = "http://192.168.1.20:3773"
        const val CODE = "one-time-pairing-code-1234"
        const val ENVIRONMENT = "8b55eaad-3149-443c-953f-afd6dd002666"
        const val LINK = "$ORIGIN/pair#token=$CODE"
    }
}
