package dev.avillagran.ot3android.core.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Schema 2. A command persisted before it is sent, so a lost acknowledgement can be resent
 * byte-identical ([commandJson]) and deduplicated by the server through its `commandId` ([id]).
 */
@Entity(tableName = "outbox", indices = [Index(value = ["environmentId", "threadId"])])
data class OutboxEntity(
    /** The command's `commandId`. */
    @PrimaryKey val id: String,
    /** The local environment id (`Environment.id`). */
    val environmentId: String,
    val threadId: String,
    /** The exact `orchestration.dispatchCommand` arguments, as sent. */
    val commandJson: String,
    val createdAt: Long,
    /** [STATE_SENDING] or [STATE_UNKNOWN]. */
    val state: String,
) {
    companion object {
        /** Persisted and being sent; at the next process start it becomes [STATE_UNKNOWN]. */
        const val STATE_SENDING = "sending"

        /** Sent, but the server's answer was lost (`rpc_outcome_unknown`). */
        const val STATE_UNKNOWN = "unknown"
    }
}

/** Schema 2. The composer's unsent text per thread, keyed by `<environment id>/<thread id>`. */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val threadKey: String,
    val text: String,
    val updatedAt: Long,
)
