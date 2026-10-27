package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class ThreadCacheAuthoritySessionTest {
    @get:Rule val root = TemporaryFolder()
    @Before fun native() = assumeHostJni()
    private fun snapshot(text: String) = ThreadFixtures.bounded(10,
        ThreadFixtures.projection(messages = listOf(ThreadFixtures.message("m1", text, "2026-10-25T01:00:00Z"))))

    @Test fun sameUrlCredentialReplacementCannotRestoreThePriorProcessCache(): Unit = runBlocking {
        val dao = dev.avillagran.ot3android.core.data.InMemoryEnvironmentDao()
        val tokens = dev.avillagran.ot3android.core.data.InMemoryTokenStore()
        fun repository() = dev.avillagran.ot3android.core.data.EnvironmentRepository(dao, { tokens }, dev.avillagran.ot3android.core.connection.MemoryCursorStore())
        val repo = repository()
        val firstToken = "synthetic-" + "a".repeat(64)
        val nextToken = "synthetic-" + "b".repeat(64)
        val env = repo.save(null, "Lab", "http://fixture.invalid", "server", firstToken)
        val directory = root.newFolder()
        val cache = ThreadCache(directory)
        val lease = cache.open(env.id, "t1", repo.cacheAuthority(env.id)!!)!!
        cache.write(lease, snapshot("prior-credential")); cache.close(lease)
        val record = directory.listFiles()!!.single().readText()
        assertFalse(record.contains(firstToken))
        assertFalse(record.contains(nextToken))
        repo.save(env.id, env.label, env.baseUrl, env.environmentId, nextToken)
        val restartedRepository = repository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ThreadSession("t1", MutableStateFlow<Connection?>(null), scope, domain = TestDomain,
            cache = ThreadCache(directory), cacheEnvironment = env.id,
            cacheAuthority = { restartedRepository.cacheAuthority(env.id) }, cacheAuthorityUpdates = restartedRepository.cacheAuthorities(env.id))
        try {
            session.start()
            withTimeout(5_000) { while (directory.listFiles().orEmpty().any { it.extension == "json" }) delay(10) }
            assertFalse(session.view.value.loaded)
            assertNull(session.projectionSnapshot())
        } finally { session.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun withdrawalWhileAlreadyOfflineClearsMemoryAndDiskAndCannotReappearOnRestart(): Unit = runBlocking {
        val directory = root.newFolder()
        val cache = ThreadCache(directory)
        val lease = cache.open("env", "t1", "owner-a")!!
        cache.write(lease, snapshot("private-a")); cache.close(lease)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = MutableStateFlow<String?>("owner-a")
        val connection = MutableStateFlow<Connection?>(null)
        fun session() = ThreadSession("t1", connection, scope, domain = TestDomain,
            cache = ThreadCache(directory), cacheEnvironment = "env", cacheAuthority = { owner.value }, cacheAuthorityUpdates = owner)
        val first = session()
        try {
            first.start()
            assertEquals("private-a", withTimeout(5_000) { first.view.first { it.loaded } }.messages.single().text)
            owner.value = null
            withTimeout(5_000) { first.view.first { !it.loaded && it.stale } }
            assertNull(first.projectionSnapshot())
            assertTrue(directory.listFiles().orEmpty().none { it.extension == "json" })
            first.close()
            owner.value = "owner-b"
            val restarted = session()
            try {
                restarted.start()
                // Wait for startup processing, using the authority resolver rather than a sleep.
                val cacheReader = ThreadCache(directory)
                assertNull(cacheReader.read(cacheReader.open("env", "t1", "owner-b")!!))
                assertFalse(restarted.view.value.loaded)
            } finally { restarted.close() }
        } finally { first.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun changedOwnerBlocksTheOldConnectionUntilItsReplacementAndDoesNotPersistOldRows(): Unit = runBlocking {
        val cache = ThreadCache(root.newFolder())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = MutableStateFlow<String?>("owner-a")
        val fake = FakeT3Transport()
        fake.connect(ConnectRoute.Bearer("http://fixture.invalid", "synthetic-token"))
        var snapshots = 0
        fake.onBoundedThread("t1") { snapshots++; snapshot(if (snapshots == 1) "private-a" else "private-b") }
        val connection = MutableStateFlow<Connection?>(Connection(fake, 1))
        val session = ThreadSession("t1", connection, scope, domain = TestDomain,
            cache = cache, cacheEnvironment = "env", cacheAuthority = { owner.value }, cacheAuthorityUpdates = owner)
        try {
            session.start()
            withTimeout(5_000) { session.view.first { it.loaded } }
            withTimeout(5_000) { fake.collectors("t1").first { it == 1 } }
            owner.value = "owner-b"
            withTimeout(5_000) { session.view.first { !it.loaded && it.stale } }
            withTimeout(5_000) { fake.collectors("t1").first { it == 0 } }
            assertEquals(1, snapshots)
            connection.value = null
            connection.value = Connection(fake, 2)
            val replacement = withTimeout(5_000) { session.view.first { it.loaded } }
            assertEquals("private-b", replacement.messages.single().text)
            assertEquals(2, snapshots)
        } finally { session.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        val saved = cache.read(cache.open("env", "t1", "owner-b")!!)!!
        assertTrue(saved.toString().contains("private-b"))
        assertFalse(saved.toString().contains("private-a"))
    }

    @Test fun immediateRestartWaitsForThePreviousLeaseFlush(): Unit = runBlocking {
        val directory = root.newFolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeT3Transport()
        fake.connect(ConnectRoute.Bearer("http://fixture.invalid", "synthetic-token"))
        fake.onBoundedThread("t1") { snapshot("saved") }
        val connection = MutableStateFlow<Connection?>(Connection(fake, 1))
        val closing = java.util.concurrent.atomic.AtomicBoolean(false)
        val gated = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = ThreadSession("t1", connection, scope, domain = TestDomain,
            cache = ThreadCache(directory), cacheEnvironment = "env", cacheAuthority = {
                if (closing.get() && gated.compareAndSet(false, true)) { entered.complete(Unit); release.await() }
                "owner"
            })
        try {
            session.start()
            withTimeout(5_000) { fake.collectors("t1").first { it == 1 } }
            closing.set(true)
            session.close()
            withTimeout(5_000) { entered.await() }
            session.start()
            assertEquals(1, fake.streamOpens.size)
            release.complete(Unit)
            withTimeout(5_000) { fake.collectors("t1").first { it == 1 } }
            assertEquals(2, fake.streamOpens.size)
            fake.emitThread("t1", ThreadUpdates.batch(cursor = 10, start = "resume", idle = true))
            withTimeout(5_000) { session.view.first { it.loaded && !it.stale } }
        } finally { release.complete(Unit); session.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        val cache = ThreadCache(directory)
        assertNotNull(cache.read(cache.open("env", "t1", "owner")!!))
    }

    @Test fun authorityInvalidatesMemoryEvenWhenNativeMembershipDidNotAllowACacheLease(): Unit = runBlocking {
        val cache = ThreadCache(root.newFolder())
        cache.reconcile("env", emptySet())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = MutableStateFlow<String?>("owner-a")
        val fake = FakeT3Transport()
        fake.connect(ConnectRoute.Bearer("http://fixture.invalid", "synthetic-token"))
        fake.onBoundedThread("t1") { snapshot("uncached-private") }
        val connection = MutableStateFlow<Connection?>(Connection(fake, 1))
        val session = ThreadSession("t1", connection, scope, domain = TestDomain,
            cache = cache, cacheEnvironment = "env", cacheAuthority = { owner.value }, cacheAuthorityUpdates = owner)
        try {
            session.start()
            withTimeout(5_000) { session.view.first { it.loaded } }
            owner.value = null
            withTimeout(5_000) { session.view.first { !it.loaded && it.stale } }
            assertNull(session.projectionSnapshot())
            withTimeout(5_000) { fake.collectors("t1").first { it == 0 } }
        } finally { session.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
