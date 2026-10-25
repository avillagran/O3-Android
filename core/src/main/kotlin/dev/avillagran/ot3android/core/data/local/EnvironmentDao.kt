package dev.avillagran.ot3android.core.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EnvironmentDao {
    @Query("SELECT * FROM environments ORDER BY sortOrder, label")
    fun observeAll(): Flow<List<EnvironmentEntity>>

    @Query("SELECT * FROM environments WHERE id = :id")
    suspend fun findById(id: String): EnvironmentEntity?

    @Upsert
    suspend fun upsert(environment: EnvironmentEntity)

    /**
     * Records a connection in place. An UPDATE, not an upsert of a row read earlier, so a
     * row deleted meanwhile stays deleted.
     */
    @Query(
        "UPDATE environments SET environmentId = COALESCE(:environmentId, environmentId), " +
            "lastConnectedAt = :connectedAt WHERE id = :id",
    )
    suspend fun markConnected(id: String, environmentId: String?, connectedAt: Long)

    @Query("DELETE FROM environments WHERE id = :id")
    suspend fun deleteById(id: String)
}
