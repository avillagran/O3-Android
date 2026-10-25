package dev.avillagran.ot3android.core.pairing

import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.MemoryCursorStore
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.InMemoryEnvironmentDao
import dev.avillagran.ot3android.core.data.InMemoryTokenStore
import dev.avillagran.ot3android.core.security.TokenStore
import dev.avillagran.ot3android.core.shell.ShellFixtures
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.FakeT3TransportFactory
import dev.avillagran.ot3android.core.transport.PairedBearer
import dev.avillagran.ot3android.core.transport.PairingClient
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class PairingServiceTest {

    private val dao = InMemoryEnvironmentDao()
    private val tokens = InMemoryTokenStore()
    private var ids = 0
    /** Runs after each token write. */
    private var afterTokenWrite: suspend (environmentId: String) -> Unit = {}
    private val watchedTokens = object : TokenStore by tokens {
        override suspend fun setToken(environmentId: String, token: String) {
            tokens.setToken(environmentId, token)
            afterTokenWrite(environmentId)
        }
    }
    private val cursors = MemoryCursorStore()
    private val repository = EnvironmentRepository(dao, { watchedTokens }, cursors, clock = { 1_000L }, newId = { "local-${++ids}" })
    private val stopped = mutableListOf<String>()
    private val handedOver = mutableListOf<String>()
    private val client = PairingClient(label = "ot3 test")

    /** What every new transport answers; tests change it before the step that uses it. */
    private var descriptorAnswer: suspend () -> JSONObject = { descriptor() }
    private var pairAnswer: suspend (FakeT3Transport.PairRequest) -> PairedBearer = { paired() }
    private val factory = FakeT3TransportFactory {
        FakeT3Transport().apply {
            onFetchDescriptor(ORIGIN) { descriptorAnswer() }
            onFetchDescriptor(OLD_ORIGIN) { descriptor() }
            onPairBearer(ORIGIN) { pairAnswer(it) }
            onShell { ShellFixtures.body(1, emptyList(), emptyList()) }
        }
    }

    private fun TestScope.service(
        stopConnection: suspend (String) -> Unit = { stopped += it },
        handOver: suspend (String) -> Unit = { handedOver += it },
    ) = PairingService(
        transportFactory = factory,
        repository = repository,
        client = client,
        stopConnection = stopConnection,
        handOver = handOver,
        io = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun pairsSavesTheIssuedTokenAndHandsOver() = runTest {
        val service = service()

        val ready = service.inspect(LINK)

        assertEquals(PairingState.Ready::class, ready::class)
        ready as PairingState.Ready
        assertEquals(ORIGIN, ready.origin)
        assertEquals("node", ready.server.label)
        assertEquals("0.1.3", ready.server.serverVersion)
        assertNull(ready.existingLabel)

        val paired = service.confirm() as PairingState.Paired

        assertEquals(PairingState.Paired("local-1", "node", updated = false), paired)
        val saved = repository.find("local-1")!!
        assertEquals(ENVIRONMENT, saved.environmentId)
        assertEquals(ORIGIN, saved.baseUrl)
        assertEquals("node", saved.label)
        assertEquals("the long-lived token, never the one-time code", ACCESS_TOKEN, tokens.tokens["local-1"])
        assertFalse(tokens.tokens.values.contains(CODE))
        assertEquals(listOf("local-1"), handedOver)
        assertTrue("nothing saved to stop", stopped.isEmpty())

        val request = factory.created.flatMap { it.pairRequests }.single()
        assertEquals(ORIGIN, request.baseUrl)
        assertEquals(CODE, request.pairingCode)
        assertEquals("the environment the user saw is checked again", ENVIRONMENT, request.environmentId)
        assertEquals(client, request.client)
        assertFalse(request.connect)
        assertTrue("every transport is closed", factory.created.all { it.closed })
    }

    @Test
    fun theSameServerAtTheSameAddressGetsTheNewToken() = runTest {
        repository.save(null, "My box", ORIGIN, ENVIRONMENT, "old-token")
        val service = service()

        val ready = service.inspect(LINK) as PairingState.Ready
        assertEquals("My box", ready.existingLabel)
        assertEquals(ORIGIN, ready.existingBaseUrl)
        assertFalse(ready.moves)
        val paired = service.confirm()

        assertEquals(PairingState.Paired("local-1", "My box", updated = true), paired)
        val all = repository.environments.first()
        assertEquals(1, all.size)
        assertEquals("the user's label stays", "My box", all.single().label)
        assertEquals(ACCESS_TOKEN, tokens.tokens["local-1"])
        assertEquals(listOf("local-1"), handedOver)
    }

    @Test
    fun aLinkToAnotherAddressAddsANewEnvironmentByDefault() = runTest {
        repository.save(null, "My box", OLD_ORIGIN, ENVIRONMENT, "old-token")
        val service = service()

        val ready = service.inspect(LINK) as PairingState.Ready
        assertEquals("My box", ready.existingLabel)
        assertEquals(OLD_ORIGIN, ready.existingBaseUrl)
        assertTrue(ready.moves)
        val paired = service.confirm()

        assertEquals(PairingState.Paired("local-2", "node", updated = false), paired)
        assertEquals(listOf(OLD_ORIGIN, ORIGIN), repository.environments.first().map { it.baseUrl })
        assertEquals("the saved environment keeps its address", OLD_ORIGIN, repository.find("local-1")!!.baseUrl)
        assertEquals("and its token", "old-token", tokens.tokens["local-1"])
        assertEquals(ACCESS_TOKEN, tokens.tokens["local-2"])
        assertEquals(listOf("local-2"), handedOver)
    }

    @Test
    fun replacingExplicitlyMovesTheSavedEnvironment() = runTest {
        repository.save(null, "My box", OLD_ORIGIN, ENVIRONMENT, "old-token")
        val service = service()
        service.inspect(LINK)

        val paired = service.confirm(replace = true)

        assertEquals(PairingState.Paired("local-1", "My box", updated = true), paired)
        val all = repository.environments.first()
        assertEquals(1, all.size)
        assertEquals("My box", all.single().label)
        assertEquals(ORIGIN, all.single().baseUrl)
        assertEquals(ACCESS_TOKEN, tokens.tokens["local-1"])
        assertEquals(listOf("local-1"), handedOver)
    }

    @Test
    fun aSavedEnvironmentIsStoppedBeforeItsTokenChanges() = runTest {
        repository.save(null, "My box", ORIGIN, ENVIRONMENT, "old-token")
        val events = mutableListOf<String>()
        afterTokenWrite = { events += "write $it" }
        val service = service(stopConnection = { events += "stop $it" }, handOver = { events += "start $it" })
        service.inspect(LINK)

        service.confirm()

        assertEquals(listOf("stop local-1", "write local-1", "start local-1"), events)
    }

    @Test
    fun aRunningSupervisorNeverSendsTheNewTokenToTheOldAddress() = runTest {
        repository.save(null, "My box", OLD_ORIGIN, ENVIRONMENT, "old-token")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = ConnectionManager(
            repository, cursors, factory, backgroundScope,
            clock = { testScheduler.currentTime }, random = Random(3), io = dispatcher, computation = dispatcher,
        )
        val old = manager.supervisor("local-1")!!
        old.start()
        runCurrent()
        assertTrue(old.state.value is ConnectionState.Connected)
        val oldTransport = factory.created.last()
        // Were the old run still going once the token is written, its stream breaking now would
        // make it reconnect (after its backoff) with the token stored by then.
        afterTokenWrite = {
            oldTransport.emitError(T3Exception("stream_timeout"))
            delay(60_000)
        }
        val service = service(
            stopConnection = manager::forget,
            handOver = { id ->
                manager.forget(id)
                manager.supervisor(id)?.start()
            },
        )
        service.inspect(LINK)

        service.confirm(replace = true)
        advanceTimeBy(120_000)
        runCurrent()

        val routes = factory.created.flatMap { it.routes }.filterIsInstance<ConnectRoute.Bearer>()
        assertFalse(old.isRunning)
        assertTrue("$routes", routes.none { it.baseUrl == OLD_ORIGIN && it.token == ACCESS_TOKEN })
        assertTrue("$routes", routes.any { it.baseUrl == ORIGIN && it.token == ACCESS_TOKEN })
        assertTrue(manager.supervisor("local-1")!!.state.value is ConnectionState.Connected)
    }

    @Test
    fun leavingDuringTheExchangeStillStoresTheEnvironmentAndToken() = runTest {
        val exchanging = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        pairAnswer = {
            exchanging.complete(Unit)
            answer.await()
            paired()
        }
        val service = service()
        service.inspect(LINK)
        val screen = launch { service.confirm() }
        exchanging.await()

        screen.cancel()
        answer.complete(Unit)
        screen.join()

        assertEquals(PairingState.Paired("local-1", "node", updated = false), service.state.value)
        assertEquals(ORIGIN, repository.find("local-1")!!.baseUrl)
        assertEquals(ACCESS_TOKEN, tokens.tokens["local-1"])
        assertEquals(listOf("local-1"), handedOver)
        assertTrue(factory.created.all { it.closed })
    }

    @Test
    fun aDescriptorWithoutLabelFallsBackToTheHost() = runTest {
        descriptorAnswer = { descriptor().apply { remove("label") } }
        pairAnswer = { paired(descriptor = descriptor().apply { remove("label") }) }
        val service = service()

        service.inspect(LINK)
        val paired = service.confirm() as PairingState.Paired

        assertEquals("192.168.1.20:3773", paired.label)
    }

    @Test
    fun pairingErrorsMapToFailuresAndSaveNothing() = runTest {
        val cases = mapOf(
            "pairing_rejected" to PairingFailure.Rejected,
            "unsupported_pairing_token" to PairingFailure.Incompatible,
            "t3_environment_mismatch" to PairingFailure.WrongServer,
            "invalid_t3_environment_id" to PairingFailure.Incompatible,
            "invalid_pairing_code" to PairingFailure.InvalidCode,
            "invalid_pairing_client" to PairingFailure.Internal,
            "invalid_endpoint" to PairingFailure.InvalidLink,
            "unreachable" to PairingFailure.Unreachable,
            "rpc_connection_failed" to PairingFailure.Unreachable,
            "not_json" to PairingFailure.NotT3,
            "malformed" to PairingFailure.NotT3,
            "http_502" to PairingFailure.NotT3,
            "internal_error" to PairingFailure.Internal,
            "something_new" to PairingFailure.Other,
        )
        cases.forEach { (code, failure) ->
            pairAnswer = { throw T3Exception(code) }
            val service = service()
            service.inspect(LINK)

            val state = service.confirm()

            assertEquals(code, PairingState.Failed(failure, code, ORIGIN), state)
            assertEquals(code, state, service.state.value)
        }
        assertTrue(repository.environments.first().isEmpty())
        assertTrue(tokens.tokens.isEmpty())
        assertTrue(handedOver.isEmpty())
    }

    @Test
    fun descriptorErrorsStopBeforeAnyCodeIsSent() = runTest {
        descriptorAnswer = { throw T3Exception("unreachable") }
        val service = service()

        val state = service.inspect(LINK)

        assertEquals(PairingState.Failed(PairingFailure.Unreachable, "unreachable", ORIGIN), state)
        assertTrue(factory.created.all { it.pairRequests.isEmpty() })
        assertEquals("nothing to confirm", state, service.confirm())
    }

    @Test
    fun linkErrorsAreFoundOffline() = runTest {
        val service = service()

        assertEquals(PairingState.Failed(PairingFailure.InvalidLink, "invalid_endpoint", null), service.inspect("ftp://node/pair#token=x"))
        assertEquals(
            PairingState.Failed(PairingFailure.InvalidCode, "invalid_pairing_code", null),
            service.inspect("https://node/pair#token=%01"),
        )
        assertEquals(
            PairingState.Failed(PairingFailure.NoCode, PairingService.NO_CODE, ORIGIN),
            service.inspect("$ORIGIN/pair"),
        )
        assertTrue("no transport for a bad link", factory.created.isEmpty())
    }

    @Test
    fun aFailureThatIsNotTheLibsIsInternalWithoutItsMessage() = runTest {
        pairAnswer = { throw IllegalStateException("boom $CODE") }
        val service = service()
        service.inspect(LINK)

        val state = service.confirm()

        assertEquals(PairingState.Failed(PairingFailure.Internal, "internal_error", ORIGIN), state)
        assertFalse(state.toString().contains(CODE))
    }

    @Test
    fun theCodeAndTokenNeverReachAStateOrALog() = runTest {
        val service = service()
        val seen = mutableListOf<PairingState>()
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) { service.state.collect { seen += it } }

        service.inspect(LINK)
        service.confirm()
        pairAnswer = { throw T3Exception("pairing_rejected") }
        service.inspect(LINK)
        service.confirm()
        watcher.cancel()

        assertTrue(seen.size >= 6)
        val texts = seen.map { it.toString() } +
            factory.created.flatMap { transport -> transport.log.map { it.toString() } + transport.pairRequests.map { it.toString() } } +
            paired().toString()
        texts.forEach { text ->
            assertFalse(text, text.contains(CODE))
            assertFalse(text, text.contains(ACCESS_TOKEN))
        }
    }

    @Test
    fun resetForgetsTheParsedLink() = runTest {
        val service = service()
        service.inspect(LINK)

        service.reset()

        assertEquals(PairingState.Idle, service.confirm())
        assertTrue(factory.created.all { it.pairRequests.isEmpty() })
    }

    @Test
    fun onlyPairingLinksWithACodeAreTakenFromAQrCode() {
        listOf(
            LINK,
            "$ORIGIN/pair?token=$CODE",
            "wss://box.example.ts.net/pair#token=$CODE",
            "https://t3.codes/connect?host=http%3A%2F%2F192.168.1.20%3A3773#token=$CODE",
        ).forEach { assertTrue(it, PairingService.isPairingLink(it)) }
        listOf(
            "$ORIGIN/pair",
            "$ORIGIN/pair#token=",
            "$ORIGIN/other#token=$CODE",
            "https://example.com/#token=$CODE",
            "https://t3.codes/connect?host=#token=$CODE",
            "ftp://node/pair#token=$CODE",
            "WIFI:S:home;T:WPA;P:secret;;",
            "just some text",
            "",
        ).forEach { assertFalse(it, PairingService.isPairingLink(it)) }
    }

    private fun descriptor() = JSONObject()
        .put("environmentId", ENVIRONMENT)
        .put("label", "node")
        .put("serverVersion", "0.1.3")
        .put("platform", JSONObject().put("os", "linux"))

    private fun paired(descriptor: JSONObject = descriptor()) = PairedBearer(
        accessToken = ACCESS_TOKEN,
        tokenType = "Bearer",
        environmentId = ENVIRONMENT,
        descriptor = descriptor,
        scope = "orchestration:read orchestration:operate",
        expiresIn = 2_592_000,
        connected = false,
    )

    private companion object {
        const val ORIGIN = "http://192.168.1.20:3773"
        const val OLD_ORIGIN = "http://box.lan:3773"
        const val CODE = "one-time-pairing-code-1234"
        const val ACCESS_TOKEN = "long-lived-access-token-5678"
        const val ENVIRONMENT = "8b55eaad-3149-443c-953f-afd6dd002666"
        const val LINK = "$ORIGIN/pair#token=$CODE"
    }
}
