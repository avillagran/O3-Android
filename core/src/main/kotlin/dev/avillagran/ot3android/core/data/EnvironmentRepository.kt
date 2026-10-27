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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val mutations: Mutex = Mutex(),
) {
    val bridgeStates = managed?.observeAll() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    val environments: Flow<List<Environment>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun find(id: String): Environment? = dao.findById(id)?.toDomain()

    private val credentialChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)

    suspend fun token(id: String): String? = mutations.withLock { tokenLocked(id) }

    /** Must be called while [mutations] is held; shared by network and local-cache readers. */
    private suspend fun tokenLocked(id: String): String? {
        // Discovery has no credential. Revoked/expired rows cannot restart streams
        // from a previously issued session while waiting for re-enrollment.
        val origin = managed?.find(id)
        if (origin != null && (origin.status != "authorized" || (origin.authorizationExpiresAt ?: 0L) <= clock())) return null
        return tokens().token(id)
    }

    /** Local cache identity only. No token, label or timestamps are returned or persisted. */
    suspend fun cacheAuthority(id: String): String? = mutations.withLock {
        val row = dao.findById(id) ?: return@withLock null
        val token = try { tokenLocked(id) } catch (_: java.io.IOException) { null }
            catch (_: java.security.GeneralSecurityException) { null } catch (_: SecurityException) { null }
        if (token.isNullOrBlank()) return@withLock null
        // Tokens are high-entropy bearer credentials. Only the domain-separated digest leaves
        // this lock; saved route + local id bind the endpoint. The advertised server UUID
        // may be learned after connection publication, so it is metadata rather than a
        // credential boundary (null → known must not block that healthy connection).
        val material = org.json.JSONArray().put("ot3-thread-cache-authority-v1")
            .put(row.id).put(row.baseUrl).put(token).toString()
        java.security.MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Does not acquire a connection or stream. Metadata-only updates have the same digest. */
    fun cacheAuthorities(id: String): Flow<String?> = kotlinx.coroutines.flow.combine(
        environments, bridgeStates, credentialChanges,
    ) { _, _, _ -> cacheAuthority(id) }.distinctUntilChanged()

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
    ): Environment = mutations.withLock {
        try {
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
            if (token != null) {
                // Room and Keystore cannot commit together. Persist a deny gate before
                // replacing a managed credential; interruption must require a retry.
                managed?.find(saved.id)?.let { managed.upsert(it.copy(status = "credential_updating")) }
                tokens().setToken(saved.id, token)
            }
            if (existing != null && existing.baseUrl != saved.baseUrl) cursors.setCursor(saved.id, null)
            // A metadata edit without a new credential must not turn a withdrawn bridge
            // session into an independent local credential and bypass the gate.
            dao.upsert(saved.toEntity())
            if (token != null) managed?.delete(saved.id)
            saved
        } finally { credentialChanges.update { it + 1 } }
    }

    /** Records a successful connection and the server's environment id; a removed environment stays removed. */
    suspend fun markConnected(id: String, environmentId: String?) {
        dao.markConnected(id, environmentId, clock())
    }

    /** Removes the row, its token and its shell cursor together. */
    suspend fun delete(id: String) = mutations.withLock {
        try {
            managed?.find(id)?.let { managed.upsert(it.copy(status = "credential_deleting")) }
            dao.deleteById(id)
            tokens().delete(id)
            cursors.setCursor(id, null)
            // Keep the gate until the credential has actually been removed, including
            // when Keystore/storage deletion fails part-way through.
            managed?.delete(id)
        } finally { credentialChanges.update { it + 1 } }
    }
}
