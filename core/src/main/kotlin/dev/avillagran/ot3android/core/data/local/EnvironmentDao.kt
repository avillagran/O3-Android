package dev.avillagran.ot3android.core.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EnvironmentDao {
    @Query("SELECT * FROM environments ORDER BY label")
    fun observeAll(): Flow<List<EnvironmentEntity>>

    @Query("SELECT * FROM environments WHERE id = :id")
    suspend fun findById(id: String): EnvironmentEntity?

    @Upsert
    suspend fun upsert(environment: EnvironmentEntity)

    @Query("DELETE FROM environments WHERE id = :id")
    suspend fun deleteById(id: String)
}
