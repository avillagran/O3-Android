package dev.avillagran.ot3android.core.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Private database only; never included in diagnostics, saved instance state or backups. */
@Entity(tableName = "shared_draft_journal")
data class SharedDraftJournal(@PrimaryKey val authority: String, val json: String)

@Dao
interface SharedDraftJournalDao {
    @Query("SELECT * FROM shared_draft_journal WHERE authority = :authority")
    suspend fun get(authority: String): SharedDraftJournal?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: SharedDraftJournal)
}
