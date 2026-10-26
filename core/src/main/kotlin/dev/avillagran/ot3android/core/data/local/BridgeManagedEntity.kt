package dev.avillagran.ot3android.core.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** Provenance is separate so existing independent environments retain their ownership. */
@Entity(tableName = "bridge_managed")
data class BridgeManagedEntity(
    @PrimaryKey val id: String,
    val provenance: String,
    val status: String,
    val authorizationExpiresAt: Long? = null,
)

@Dao
interface BridgeManagedDao {
    @Query("SELECT * FROM bridge_managed")
    suspend fun all(): List<BridgeManagedEntity>

    @Query("SELECT * FROM bridge_managed WHERE id = :id")
    suspend fun find(id: String): BridgeManagedEntity?

    @Upsert
    suspend fun upsert(row: BridgeManagedEntity)

    @Query("DELETE FROM bridge_managed WHERE id = :id")
    suspend fun delete(id: String)
}
