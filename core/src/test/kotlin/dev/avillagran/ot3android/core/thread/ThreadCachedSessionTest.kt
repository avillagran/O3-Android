package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.transport.*
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThreadCachedSessionTest {
    @get:Rule val root = TemporaryFolder()
    @Before fun native() = assumeHostJni()

    @Test fun offlineRestartThenIncrementalStreamingAndSnapshotFallback() = runBlocking {
        val directory = root.newFolder()
        val cache = ThreadCache(directory)
        val snapshot = ThreadFixtures.bounded(10, ThreadFixtures.projection(messages = listOf(ThreadFixtures.message("m1", "saved", "2026-10-25T01:00:00Z"))))
        val lease = cache.open("env", "t1", "server")!!
        cache.write(lease, snapshot)
        cache.close(lease)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = MutableStateFlow<Connection?>(null)
        val fake = FakeT3Transport()
        var snapshots = 0
        fake.onBoundedThread("t1") { snapshots++; ThreadFixtures.bounded(30, ThreadFixtures.projection(messages = listOf(ThreadFixtures.message("m3", "replacement", "2026-10-25T01:00:00Z")))) }
        val session = ThreadSession("t1", connection, scope, domain = TestDomain,
            cache = ThreadCache(directory), cacheEnvironment = "env", cacheAuthority = { "server" }, refetchIntervalMs = 0)
        try {
            session.start()
            val saved = withTimeout(5_000) { session.view.first { it.loaded } }
            assertEquals("saved", saved.messages.single().text)
            assertTrue(saved.stale)
            assertEquals(0, snapshots)
            fake.connect(ConnectRoute.Bearer("http://fixture.test", "fixture-token"))
            connection.value = Connection(fake, 1)
            withTimeout(5_000) { fake.collectors("t1").first { it > 0 } }
            assertEquals(10L, fake.streamOpens.single().afterSequence)
            fake.emitThread("t1", ThreadUpdates.batch(ThreadFixtures.event(11, "message.updated", ThreadFixtures.message("m1", "streamed", "2026-10-25T01:00:00Z", streaming = true)), cursor = 11, start = "resume"))
            val streamed = withTimeout(5_000) { session.view.first { it.sequence == 11L } }
            assertEquals(listOf("m1"), streamed.messages.map { it.id })
            assertEquals("streamed", streamed.messages.single().text)
            assertFalse(streamed.stale)
            assertEquals(0, snapshots)
            // The native stream may replace the saved cursor with a snapshot.
            fake.emitThread("t1", ThreadUpdates.batch(ThreadFixtures.snapshotItem(20, ThreadFixtures.projection(messages = listOf(ThreadFixtures.message("m2", "snapshot", "2026-10-25T01:00:00Z")))), cursor = 20, start = "snapshot"))
            val fresh = withTimeout(5_000) { session.view.first { it.sequence == 20L } }
            assertEquals(listOf("m2"), fresh.messages.map { it.id })
            connection.value = null
            withTimeout(5_000) { session.view.first { it.connectionState == ThreadConnection.Waiting } }
            fake.failNextStream(T3Exception("cursor_expired"))
            connection.value = Connection(fake, 2)
            val fallback = withTimeout(5_000) { session.view.first { it.sequence == 30L } }
            assertEquals(listOf("m3"), fallback.messages.map { it.id })
            assertEquals(1, snapshots)
            connection.value = null
            withTimeout(5_000) { session.view.first { it.connectionState == ThreadConnection.Waiting } }
            connection.value = Connection(fake, 3)
            // A quiet server sends no frame: bounded validation must still release stale UI.
            withTimeout(8_000) { session.view.first { !it.stale } }
            assertEquals(2, snapshots)
        } finally { session.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        assertEquals(0, fake.collectors("t1").value)
        val restored = ThreadCache(directory)
        assertEquals(30L, restored.read(restored.open("env", "t1", "server")!!)!!.getLong("snapshotSequence"))
    }
}
