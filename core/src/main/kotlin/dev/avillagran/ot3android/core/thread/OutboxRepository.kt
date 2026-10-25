package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.data.local.DraftDao
import dev.avillagran.ot3android.core.data.local.DraftEntity
import dev.avillagran.ot3android.core.data.local.OutboxDao
import dev.avillagran.ot3android.core.data.local.OutboxEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** A command waiting in the outbox, as the screen offers it for retry. */
data class PendingCommand(
    val commandId: String,
    val environmentId: String,
    val threadId: String,
    /** The exact arguments sent; a retry sends these bytes again. */
    val commandJson: String,
    val createdAt: Long,
    /** [OutboxEntity.STATE_SENDING] or [OutboxEntity.STATE_UNKNOWN]. */
    val state: String,
) {
    /** The message text, for display. */
    val text: String? get() = runCatching { JSONObject(commandJson).optString("text").ifEmpty { null } }.getOrNull()
}

/** Non-repeatable commands persisted before they are sent (see [MessageSender]). */
class OutboxRepository(private val dao: OutboxDao, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun add(environmentId: String, threadId: String, commandId: String, commandJson: String): PendingCommand {
        val entry = OutboxEntity(commandId, environmentId, threadId, commandJson, clock(), OutboxEntity.STATE_SENDING)
        dao.insert(entry)
        return entry.toPending()
    }

    suspend fun find(commandId: String): PendingCommand? = dao.findById(commandId)?.toPending()

    suspend fun setState(commandId: String, state: String) = dao.setState(commandId, state)

    suspend fun remove(commandId: String) = dao.deleteById(commandId)

    fun observeThread(environmentId: String, threadId: String): Flow<List<PendingCommand>> =
        dao.observeThread(environmentId, threadId).map { rows -> rows.map { it.toPending() } }
}

fun OutboxEntity.toPending() = PendingCommand(id, environmentId, threadId, commandJson, createdAt, state)

/** The composer's unsent text per thread. Empty text deletes the draft. */
class DraftRepository(private val dao: DraftDao, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun load(environmentId: String, threadId: String): String =
        dao.find(key(environmentId, threadId))?.text.orEmpty()

    suspend fun save(environmentId: String, threadId: String, text: String) {
        val key = key(environmentId, threadId)
        if (text.isEmpty()) dao.delete(key) else dao.upsert(DraftEntity(key, text, clock()))
    }

    suspend fun clear(environmentId: String, threadId: String) = dao.delete(key(environmentId, threadId))

    companion object {
        fun key(environmentId: String, threadId: String) = "$environmentId/$threadId"
    }
}
