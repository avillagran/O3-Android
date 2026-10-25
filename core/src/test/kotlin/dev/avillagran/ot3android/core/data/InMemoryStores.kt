package dev.avillagran.ot3android.core.data

import dev.avillagran.ot3android.core.data.local.EnvironmentDao
import dev.avillagran.ot3android.core.data.local.EnvironmentEntity
import dev.avillagran.ot3android.core.security.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** [EnvironmentDao] over a map, for JVM tests. */
class InMemoryEnvironmentDao : EnvironmentDao {
    val rows = MutableStateFlow<Map<String, EnvironmentEntity>>(emptyMap())
    override fun observeAll(): Flow<List<EnvironmentEntity>> =
        rows.map { it.values.sortedWith(compareBy({ e -> e.sortOrder }, { e -> e.label })) }
    override suspend fun findById(id: String) = rows.value[id]
    override suspend fun upsert(environment: EnvironmentEntity) = rows.update { it + (environment.id to environment) }
    override suspend fun deleteById(id: String) = rows.update { it - id }
    override suspend fun markConnected(id: String, environmentId: String?, connectedAt: Long) = rows.update { current ->
        val row = current[id] ?: return@update current
        current + (id to row.copy(environmentId = environmentId ?: row.environmentId, lastConnectedAt = connectedAt))
    }
}

/** [TokenStore] over a map, for JVM tests. */
class InMemoryTokenStore : TokenStore {
    val tokens = mutableMapOf<String, String>()
    override suspend fun token(environmentId: String) = tokens[environmentId]
    override suspend fun setToken(environmentId: String, token: String) { tokens[environmentId] = token }
    override suspend fun delete(environmentId: String) { tokens.remove(environmentId) }
}
