package dev.avillagran.ot3android.core.data

import dev.avillagran.ot3android.core.connection.MemoryCursorStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnvironmentRepositoryTest {

    private val dao = InMemoryEnvironmentDao()
    private val tokens = InMemoryTokenStore()
    private val cursors = MemoryCursorStore()
    private var now = 1_000L
    private var ids = 0
    private val repository = EnvironmentRepository(dao, { tokens }, cursors, clock = { now }, newId = { "id-${++ids}" })

    @Test fun deletingEnvironmentAlsoInvalidatesImageCacheAndPendingWrites() = runTest {
        repository.save(null, "Workstation", "http://box:3773", "env-1", "secret-1")
        val cache = dev.avillagran.ot3android.core.thread.ReaderImageCache.shared
        val scope = cache.scope("id-1", "t", "digest")
        cache.put(scope, "image", byteArrayOf(1))
        repository.delete("id-1")
        cache.put(scope, "late", byteArrayOf(2))
        assertNull(cache.get(scope, "image"))
        assertNull(cache.get(scope, "late"))
    }

    @Test
    fun saveCreatesARowAndStoresTheTokenApart() = runTest {
        val saved = repository.save(null, "Workstation", "http://box:3773", "env-1", "secret-1")

        assertEquals("id-1", saved.id)
        assertEquals(1_000L, saved.createdAt)
        assertEquals(0, saved.sortOrder)
        assertEquals("secret-1", repository.token("id-1"))
        assertEquals(listOf("Workstation"), repository.environments.first().map { it.label })
        assertEquals(1, repository.save(null, "Laptop", "http://laptop:3773", "env-2", "secret-2").sortOrder)
    }

    @Test
    fun theSameServerIsUpdatedNotDuplicatedAndANullTokenKeepsTheOldOne() = runTest {
        repository.save(null, "Workstation", "http://box:3773", "env-1", "secret-1")
        cursors.setCursor("id-1", 42)

        val again = repository.save(null, "Renamed", "http://box.lan:3773", "env-1", token = null)

        assertEquals("id-1", again.id)
        assertEquals(1, repository.environments.first().size)
        assertEquals("Renamed", repository.find("id-1")?.label)
        assertEquals("secret-1", repository.token("id-1"))
        assertNull("a new URL drops the shell cursor", cursors.cursor("id-1"))
    }

    @Test
    fun withoutMatchingTheServerASecondRowIsAdded() = runTest {
        repository.save(null, "Workstation", "http://box:3773", "env-1", "secret-1")

        val second = repository.save(null, "Workstation", "http://other:3773", "env-1", "secret-2", matchServer = false)

        assertEquals("id-2", second.id)
        assertEquals(listOf("http://box:3773", "http://other:3773"), repository.environments.first().map { it.baseUrl })
        assertEquals("secret-1", repository.token("id-1"))
        assertEquals("secret-2", repository.token("id-2"))
    }

    @Test
    fun markConnectedRecordsTimeAndServerId() = runTest {
        repository.save(null, "Workstation", "http://box:3773", null, "secret-1")
        now = 5_000

        repository.markConnected("id-1", "env-1")

        val environment = repository.find("id-1")!!
        assertEquals(5_000L, environment.lastConnectedAt)
        assertEquals("env-1", environment.environmentId)
    }

    @Test
    fun markConnectedNeverBringsBackARemovedEnvironment() = runTest {
        repository.save(null, "Workstation", "http://box:3773", null, "secret-1")
        repository.delete("id-1")

        repository.markConnected("id-1", "env-1")

        assertNull(repository.find("id-1"))
    }

    @Test
    fun deleteRemovesRowTokenAndCursor() = runTest {
        repository.save(null, "Workstation", "http://box:3773", "env-1", "secret-1")
        cursors.setCursor("id-1", 7)

        repository.delete("id-1")

        assertNull(repository.find("id-1"))
        assertNull(repository.token("id-1"))
        assertNull(cursors.cursor("id-1"))
    }
}
