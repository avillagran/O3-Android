package dev.avillagran.ot3android.core.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** A row holding a long-lived write session from an all-hosts bridge. */
const val NATIVE_AUTHORIZED = "native_authorized"

/** That session ended; the row stays native and waits for a renewal. */
const val NATIVE_EXPIRED = "native_expired"

/** Provenance is separate so existing independent environments retain their ownership. */
@Entity(tableName = "bridge_managed")
data class BridgeManagedEntity(
    @PrimaryKey val id: String,
    val provenance: String,
    val status: String,
    val authorizationExpiresAt: Long? = null,
) {
    /** The status to show: a row no refresh reaches keeps a status its session outlived. */
    fun effectiveStatus(now: Long): String = when {
        (authorizationExpiresAt ?: 0L) > now -> status
        status == NATIVE_AUTHORIZED -> NATIVE_EXPIRED
        status == "authorized" -> "expired"
        else -> status
    }
}

@Dao
interface BridgeManagedDao {
    @Query("SELECT * FROM bridge_managed")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<BridgeManagedEntity>>

    @Query("SELECT * FROM bridge_managed")
    suspend fun all(): List<BridgeManagedEntity>

    @Query("SELECT * FROM bridge_managed WHERE id = :id")
    suspend fun find(id: String): BridgeManagedEntity?

    @Upsert
    suspend fun upsert(row: BridgeManagedEntity)

    @Query("DELETE FROM bridge_managed WHERE id = :id")
    suspend fun delete(id: String)
}
