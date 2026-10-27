package dev.avillagran.ot3android.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    /** Fails if the command id already exists: an id is minted once. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun findById(id: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE environmentId = :environmentId AND threadId = :threadId ORDER BY createdAt")
    fun observeThread(environmentId: String, threadId: String): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox WHERE environmentId = :environmentId ORDER BY createdAt")
    fun observeEnvironment(environmentId: String): Flow<List<OutboxEntity>>

    @Query("UPDATE outbox SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: String)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Marks every command still `sending` that was created before [before] as `unknown`; returns how many. */
    @Query("UPDATE outbox SET state = 'unknown' WHERE state = 'sending' AND createdAt < :before")
    suspend fun markInterrupted(before: Long): Int
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE threadKey = :threadKey")
    suspend fun find(threadKey: String): DraftEntity?

    @Upsert
    suspend fun upsert(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE threadKey = :threadKey")
    suspend fun delete(threadKey: String)
}
