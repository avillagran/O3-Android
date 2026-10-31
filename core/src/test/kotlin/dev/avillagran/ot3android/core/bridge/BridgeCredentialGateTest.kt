package dev.avillagran.ot3android.core.bridge

import dev.avillagran.ot3android.core.connection.ShellCursorStore
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.local.BridgeManagedDao
import dev.avillagran.ot3android.core.data.local.BridgeManagedEntity
import dev.avillagran.ot3android.core.data.local.EnvironmentDao
import dev.avillagran.ot3android.core.data.local.EnvironmentEntity
import dev.avillagran.ot3android.core.data.local.NATIVE_AUTHORIZED
import dev.avillagran.ot3android.core.data.local.NATIVE_EXPIRED
import dev.avillagran.ot3android.core.security.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BridgeCredentialGateTest {
    @Test
    fun anExpiredSessionNeverReadsAsAuthorized() {
        fun row(status: String, expires: Long?) = BridgeManagedEntity("node", "bridge:owner:client", status, expires)
        assertEquals("authorized", row("authorized", 200).effectiveStatus(100))
        assertEquals("Orphaned read-only row, field case", "expired", row("authorized", 100).effectiveStatus(100))
        assertEquals(NATIVE_AUTHORIZED, row(NATIVE_AUTHORIZED, 200).effectiveStatus(100))
        assertEquals(NATIVE_EXPIRED, row(NATIVE_AUTHORIZED, 100).effectiveStatus(100))
        assertEquals(NATIVE_EXPIRED, row(NATIVE_EXPIRED, null).effectiveStatus(100))
        assertEquals("revoked", row("revoked", null).effectiveStatus(100))
    }

    @Test
    fun discoveryRevocationAndExpiryCannotReuseAPreviousCredential() = runBlocking {
        val dao = object : EnvironmentDao {
            val rows = MutableStateFlow<List<EnvironmentEntity>>(emptyList())
            override fun observeAll() = rows
            override suspend fun findById(id: String) = rows.value.find { it.id == id }
            override suspend fun upsert(environment: EnvironmentEntity) { rows.value = rows.value.filterNot { it.id == environment.id } + environment }
            override suspend fun markConnected(id: String, environmentId: String?, connectedAt: Long) = Unit
            override suspend fun deleteById(id: String) { rows.value = rows.value.filterNot { it.id == id } }
        }
        val managed = object : BridgeManagedDao {
            var row: BridgeManagedEntity? = null
            override fun observeAll() = kotlinx.coroutines.flow.flow { emit(all()) }
            override suspend fun all() = listOfNotNull(row)
            override suspend fun find(id: String) = row?.takeIf { it.id == id }
            override suspend fun upsert(row: BridgeManagedEntity) { this.row = row }
            override suspend fun delete(id: String) { if (row?.id == id) row = null }
        }
        var failCredentialDelete = false
        val tokens = object : TokenStore {
            override suspend fun token(environmentId: String) = "isolated-token"
            override suspend fun setToken(environmentId: String, token: String) = Unit
            override suspend fun delete(environmentId: String) {
                if (failCredentialDelete) error("isolated-storage-failure")
            }
        }
        val cursors = object : ShellCursorStore {
            override suspend fun cursor(environmentId: String): Long? = 7
            override suspend fun setCursor(environmentId: String, cursor: Long?) = Unit
        }
        var now = 100L
        val repository = EnvironmentRepository(dao, { tokens }, cursors, clock = { now }, managed = managed)
        assertEquals("isolated-token", repository.token("independent"))
        for (status in listOf("discovered", "revoked", "expired")) {
            managed.row = BridgeManagedEntity("node", "bridge:owner:client", status, 200)
            assertNull(repository.token("node"))
        }
        managed.row = BridgeManagedEntity("node", "bridge:owner:client", "authorized", 200)
        assertEquals("isolated-token", repository.token("node"))
        managed.row = BridgeManagedEntity("node", "bridge:owner:client", NATIVE_AUTHORIZED, 200)
        assertEquals("A native row needs its session, not a fresh catalog", "isolated-token", repository.token("node"))
        managed.row = BridgeManagedEntity("node", "bridge:owner:client", NATIVE_EXPIRED, 200)
        assertNull("native_expired means the session ended", repository.token("node"))
        managed.row = BridgeManagedEntity("node", "bridge:owner:client", NATIVE_AUTHORIZED, 100)
        assertNull("The session's own end closes it before any refresh", repository.token("node"))
        managed.row = BridgeManagedEntity("node", "bridge:owner:client", "credential_updating", 200)
        assertNull(repository.token("node"))
        managed.row = BridgeManagedEntity("node", "bridge:owner:client", "authorized", 200)
        now = 200
        assertNull(repository.token("node"))
        assertEquals("isolated-token", repository.token("independent"))
        assertEquals(7L, cursors.cursor("node"))
        dao.upsert(EnvironmentEntity("node", "NODE", "https://node.example", "node", 1, null, 0))
        managed.row = BridgeManagedEntity("node", "bridge:owner:client", "revoked", 300)
        repository.save("node", "Renamed NODE", "https://node.example", "node", null)
        assertNotNull("Renaming without a new credential must retain bridge ownership", managed.row)
        assertNull("An edit must not restore the withdrawn credential", repository.token("node"))
        try {
            repository.save("node", "Changed route", "https://other.example", "node", null)
            fail("A route edit cannot reuse a managed credential on a different host")
        } catch (_: IllegalArgumentException) { }
        assertEquals("https://node.example", repository.find("node")!!.baseUrl)
        repository.save("node", "Independent NODE", "https://node.example", "node", "new-independent-token")
        assertNull("An explicit new credential can take local ownership", managed.row)
        managed.row = BridgeManagedEntity("node", "bridge:owner:client", "revoked", 300)
        failCredentialDelete = true
        try { repository.delete("node"); fail("Expected storage failure") } catch (_: IllegalStateException) { }
        assertNotNull("Partial deletion keeps the gate until the credential is removed", managed.row)
        assertNull(repository.token("node"))
    }
}
