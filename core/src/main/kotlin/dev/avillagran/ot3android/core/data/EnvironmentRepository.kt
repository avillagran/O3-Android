package dev.avillagran.ot3android.core.data

import dev.avillagran.ot3android.core.connection.ShellCursorStore
import dev.avillagran.ot3android.core.data.local.EnvironmentDao
import dev.avillagran.ot3android.core.data.local.toDomain
import dev.avillagran.ot3android.core.data.local.toEntity
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.security.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The saved environments: rows in Room, tokens in the [TokenStore] (Keystore-backed), shell
 * cursors in the [ShellCursorStore]. Every write keeps the three consistent; tokens are never
 * returned in an [Environment] and never logged.
 */
class EnvironmentRepository(
    private val dao: EnvironmentDao,
    private val tokens: suspend () -> TokenStore,
    private val cursors: ShellCursorStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val managed: dev.avillagran.ot3android.core.data.local.BridgeManagedDao? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val environments: Flow<List<Environment>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun find(id: String): Environment? = dao.findById(id)?.toDomain()

    suspend fun token(id: String): String? {
        // Discovery has no credential. Revoked/expired rows cannot restart streams
        // from a previously issued session while waiting for re-enrollment.
        val origin = managed?.find(id)
        if (origin != null && (origin.status != "authorized" || (origin.authorizationExpiresAt ?: 0L) <= clock())) return null
        return tokens().token(id)
    }

    /**
     * Creates or updates an environment. With [id] null, an existing entry for the same
     * server ([environmentId]) is updated instead of adding a duplicate, unless [matchServer]
     * is false (the user chose to keep both). A null [token] keeps the stored one. Returns the
     * saved environment.
     */
    suspend fun save(
        id: String?,
        label: String,
        baseUrl: String,
        environmentId: String?,
        token: String?,
        matchServer: Boolean = true,
    ): Environment {
        val all = environments.first()
        val existing = all.firstOrNull { it.id == id }
            ?: environmentId?.takeIf { matchServer }?.let { serverId -> all.firstOrNull { it.environmentId == serverId } }
        val saved = existing?.copy(
            label = label,
            baseUrl = baseUrl,
            environmentId = environmentId ?: existing.environmentId,
        ) ?: Environment(
            id = id ?: newId(),
            label = label,
            baseUrl = baseUrl,
            environmentId = environmentId,
            createdAt = clock(),
            sortOrder = (all.maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        if (token == null && managed?.find(saved.id) != null) {
            require(existing != null && existing.baseUrl == saved.baseUrl && existing.environmentId == saved.environmentId) {
                "managed_environment_requires_new_credential"
            }
        }
        if (token != null) tokens().setToken(saved.id, token)
        if (existing != null && existing.baseUrl != saved.baseUrl) cursors.setCursor(saved.id, null)
        // A metadata edit without a new credential must not turn a withdrawn bridge
        // session into an independent local credential and bypass the gate.
        dao.upsert(saved.toEntity())
        if (token != null) managed?.delete(saved.id)
        return saved
    }

    /** Records a successful connection and the server's environment id; a removed environment stays removed. */
    suspend fun markConnected(id: String, environmentId: String?) {
        dao.markConnected(id, environmentId, clock())
    }

    /** Removes the row, its token and its shell cursor together. */
    suspend fun delete(id: String) {
        dao.deleteById(id)
        tokens().delete(id)
        cursors.setCursor(id, null)
        // Keep the gate until the credential has actually been removed, including
        // when Keystore/storage deletion fails part-way through.
        managed?.delete(id)
    }
}
