package dev.avillagran.ot3android.core.data

import dev.avillagran.ot3android.core.connection.MemoryCursorStore
import dev.avillagran.ot3android.core.data.local.*
import dev.avillagran.ot3android.core.security.TokenStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CacheAuthorityTest {
    private val first = "synthetic-credential-" + "a".repeat(64)
    private val second = "synthetic-credential-" + "b".repeat(64)

    @Test fun sameHostCredentialRotationSurvivesRepositoryRestartButMetadataDoesNotInvalidate() = runTest {
        val dao = InMemoryEnvironmentDao()
        val tokens = InMemoryTokenStore()
        fun repository() = EnvironmentRepository(dao, { tokens }, MemoryCursorStore())
        val repository = repository()
        val env = repository.save(null, "Lab", "http://lab.invalid", "server", first)
        val original = repository.cacheAuthority(env.id)!!
        assertEquals(64, original.length)
        assertFalse(original.contains(first))
        repository.markConnected(env.id, "server")
        repository.save(env.id, "Renamed", env.baseUrl, env.environmentId, null)
        assertEquals(original, repository.cacheAuthority(env.id))
        repository.save(env.id, "Renamed", env.baseUrl, env.environmentId, second)
        val changed = repository().cacheAuthority(env.id)!!
        assertNotEquals(original, changed)
        assertFalse(changed.contains(second))
        repository.save(env.id, "Renamed", "http://other.invalid", env.environmentId, null)
        assertNotEquals(changed, repository.cacheAuthority(env.id))
        tokens.delete(env.id)
        assertNull(repository.cacheAuthority(env.id))
    }

    @Test fun sameRowCredentialWriteEmitsWhileMetadataKeepsAuthorityStable() = runTest {
        val dao = InMemoryEnvironmentDao()
        val tokens = InMemoryTokenStore()
        val repo = EnvironmentRepository(dao, { tokens }, MemoryCursorStore())
        val env = repo.save(null, "Lab", "http://lab.invalid", "server", first)
        val seen = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.cacheAuthorities(env.id).toList(seen) }
        runCurrent()
        assertEquals(1, seen.size)
        repo.markConnected(env.id, "server")
        repo.save(env.id, "Renamed", env.baseUrl, env.environmentId, null)
        runCurrent()
        assertEquals(1, seen.size)
        // Exact same Room entity: this fake coalesces the upsert, so the credential signal is required.
        repo.save(env.id, "Renamed", env.baseUrl, env.environmentId, second)
        runCurrent()
        assertEquals(2, seen.size)
        assertNotEquals(seen[0], seen[1])
        repo.delete(env.id)
        runCurrent()
        assertNull(seen.last())
    }

    @Test fun fingerprintCannotObserveHalfOfACredentialAndRouteMutation() = runBlocking {
        val dao = InMemoryEnvironmentDao()
        val backing = InMemoryTokenStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokens = object : TokenStore by backing {
            override suspend fun setToken(environmentId: String, token: String) {
                backing.setToken(environmentId, token)
                if (token == second) { entered.complete(Unit); release.await() }
            }
        }
        val repo = EnvironmentRepository(dao, { tokens }, MemoryCursorStore())
        val env = repo.save(null, "Lab", "http://lab.invalid", "server", first)
        val before = repo.cacheAuthority(env.id)
        val save = async { repo.save(env.id, env.label, "http://new.invalid", "server-new", second) }
        entered.await()
        val read = async(start = CoroutineStart.UNDISPATCHED) { repo.cacheAuthority(env.id) }
        assertFalse(read.isCompleted)
        release.complete(Unit)
        save.await()
        assertEquals(repo.cacheAuthority(env.id), read.await())
        assertNotEquals(before, read.await())
    }

    @Test fun cacheUsesTheExistingManagedCredentialGateAndDeniesUnavailableStorage() = runTest {
        val dao = InMemoryEnvironmentDao()
        val tokens = InMemoryTokenStore()
        val rows = MutableStateFlow<List<BridgeManagedEntity>>(emptyList())
        val managed = object : BridgeManagedDao {
            override fun observeAll(): Flow<List<BridgeManagedEntity>> = rows
            override suspend fun all() = rows.value
            override suspend fun find(id: String) = rows.value.find { it.id == id }
            override suspend fun upsert(row: BridgeManagedEntity) { rows.value = rows.value.filterNot { it.id == row.id } + row }
            override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
        }
        var now = 10L
        val repo = EnvironmentRepository(dao, { tokens }, MemoryCursorStore(), clock = { now }, managed = managed)
        val env = repo.save(null, "Lab", "http://lab.invalid", "server", first)
        val allowed = repo.cacheAuthority(env.id)
        managed.upsert(BridgeManagedEntity(env.id, "lab", "authorized", 100))
        assertEquals(allowed, repo.cacheAuthority(env.id))
        managed.upsert(managed.find(env.id)!!.copy(status = "revoked"))
        assertNull(repo.token(env.id))
        assertNull(repo.cacheAuthority(env.id))
        managed.upsert(managed.find(env.id)!!.copy(status = "authorized"))
        now = 100
        assertNull(repo.token(env.id))
        assertNull(repo.cacheAuthority(env.id))
        val broken = EnvironmentRepository(dao, { throw java.io.IOException("synthetic storage failure") }, MemoryCursorStore())
        assertNull(broken.cacheAuthority(env.id))
    }
}
