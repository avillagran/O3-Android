package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.data.local.DraftDao
import dev.avillagran.ot3android.core.data.local.DraftEntity
import dev.avillagran.ot3android.core.data.local.OutboxDao
import dev.avillagran.ot3android.core.data.local.OutboxEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The outbox table in memory, with Room's semantics for the queries [OutboxDao] declares. */
class MemoryOutboxDao : OutboxDao {
    val rows = MutableStateFlow<Map<String, OutboxEntity>>(emptyMap())

    override suspend fun insert(entry: OutboxEntity) {
        check(entry.id !in rows.value) { "duplicate ${entry.id}" }
        rows.update { it + (entry.id to entry) }
    }

    override suspend fun findById(id: String) = rows.value[id]

    override fun observeThread(environmentId: String, threadId: String): Flow<List<OutboxEntity>> =
        rows.map { all ->
            all.values.filter { it.environmentId == environmentId && it.threadId == threadId }.sortedBy { it.createdAt }
        }

    override fun observeEnvironment(environmentId: String): Flow<List<OutboxEntity>> =
        rows.map { all -> all.values.filter { it.environmentId == environmentId }.sortedBy { it.createdAt } }

    override suspend fun setState(id: String, state: String) =
        rows.update { all -> all[id]?.let { all + (id to it.copy(state = state)) } ?: all }

    override suspend fun checkpoint(id: String, commandJson: String) =
        rows.update { all -> all[id]?.let { all + (id to it.copy(commandJson = commandJson)) } ?: all }

    override suspend fun deleteById(id: String) = rows.update { it - id }

    override suspend fun markInterrupted(before: Long): Int {
        var changed = 0
        rows.update { all ->
            changed = 0
            all.mapValues { (_, row) ->
                if (row.state != OutboxEntity.STATE_UNKNOWN && row.createdAt < before) {
                    changed++
                    row.copy(state = OutboxEntity.STATE_UNKNOWN)
                } else {
                    row
                }
            }
        }
        return changed
    }
}

/** The drafts table in memory. */
class MemoryDraftDao : DraftDao {
    val rows = MutableStateFlow<Map<String, DraftEntity>>(emptyMap())

    override suspend fun find(threadKey: String) = rows.value[threadKey]

    override suspend fun upsert(draft: DraftEntity) = rows.update { it + (draft.threadKey to draft) }

    override suspend fun delete(threadKey: String) = rows.update { it - threadKey }
}
