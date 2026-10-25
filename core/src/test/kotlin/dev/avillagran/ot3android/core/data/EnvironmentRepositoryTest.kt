package dev.avillagran.ot3android.core.data

import dev.avillagran.ot3android.core.connection.MemoryCursorStore
import dev.avillagran.ot3android.core.data.local.EnvironmentDao
import dev.avillagran.ot3android.core.data.local.EnvironmentEntity
import dev.avillagran.ot3android.core.security.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnvironmentRepositoryTest {

    private class FakeDao : EnvironmentDao {
        val rows = MutableStateFlow<Map<String, EnvironmentEntity>>(emptyMap())
        override fun observeAll(): Flow<List<EnvironmentEntity>> =
            rows.map { it.values.sortedWith(compareBy({ e -> e.sortOrder }, { e -> e.label })) }
        override suspend fun findById(id: String) = rows.value[id]
        override suspend fun upsert(environment: EnvironmentEntity) = rows.update { it + (environment.id to environment) }
        override suspend fun deleteById(id: String) = rows.update { it - id }
    }

    private class FakeTokens : TokenStore {
        val tokens = mutableMapOf<String, String>()
        override suspend fun token(environmentId: String) = tokens[environmentId]
        override suspend fun setToken(environmentId: String, token: String) { tokens[environmentId] = token }
        override suspend fun delete(environmentId: String) { tokens.remove(environmentId) }
    }

    private val dao = FakeDao()
    private val tokens = FakeTokens()
    private val cursors = MemoryCursorStore()
    private var now = 1_000L
    private var ids = 0
    private val repository = EnvironmentRepository(dao, { tokens }, cursors, clock = { now }, newId = { "id-${++ids}" })

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
    fun markConnectedRecordsTimeAndServerId() = runTest {
        repository.save(null, "Workstation", "http://box:3773", null, "secret-1")
        now = 5_000

        repository.markConnected("id-1", "env-1")

        val environment = repository.find("id-1")!!
        assertEquals(5_000L, environment.lastConnectedAt)
        assertEquals("env-1", environment.environmentId)
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
