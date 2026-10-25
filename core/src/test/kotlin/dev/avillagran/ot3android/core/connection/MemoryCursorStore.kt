package dev.avillagran.ot3android.core.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [ShellCursorStore] for tests. */
class MemoryCursorStore : ShellCursorStore {
    val cursors = MutableStateFlow<Map<String, Long>>(emptyMap())

    override suspend fun cursor(environmentId: String): Long? = cursors.value[environmentId]

    override suspend fun setCursor(environmentId: String, cursor: Long?) {
        cursors.update { if (cursor == null) it - environmentId else it + (environmentId to cursor) }
    }
}
