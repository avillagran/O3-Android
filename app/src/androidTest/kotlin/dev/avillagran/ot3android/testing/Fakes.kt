package dev.avillagran.ot3android.testing

import dev.avillagran.ot3android.core.connection.ShellCursorStore
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.local.EnvironmentDao
import dev.avillagran.ot3android.core.data.local.EnvironmentEntity
import dev.avillagran.ot3android.core.security.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeEnvironmentDao : EnvironmentDao {
    val rows = MutableStateFlow<Map<String, EnvironmentEntity>>(emptyMap())
    override fun observeAll(): Flow<List<EnvironmentEntity>> = rows.map { it.values.sortedBy { e -> e.sortOrder } }
    override suspend fun findById(id: String) = rows.value[id]
    override suspend fun upsert(environment: EnvironmentEntity) = rows.update { it + (environment.id to environment) }
    override suspend fun deleteById(id: String) = rows.update { it - id }
}

class FakeTokenStore : TokenStore {
    val tokens = mutableMapOf<String, String>()
    override suspend fun token(environmentId: String) = tokens[environmentId]
    override suspend fun setToken(environmentId: String, token: String) { tokens[environmentId] = token }
    override suspend fun delete(environmentId: String) { tokens.remove(environmentId) }
}

class FakeCursorStore : ShellCursorStore {
    val cursors = mutableMapOf<String, Long>()
    override suspend fun cursor(environmentId: String) = cursors[environmentId]
    override suspend fun setCursor(environmentId: String, cursor: Long?) {
        if (cursor == null) cursors.remove(environmentId) else cursors[environmentId] = cursor
    }
}

fun fakeRepository(
    dao: FakeEnvironmentDao = FakeEnvironmentDao(),
    tokens: FakeTokenStore = FakeTokenStore(),
) = EnvironmentRepository(dao, { tokens }, FakeCursorStore())
