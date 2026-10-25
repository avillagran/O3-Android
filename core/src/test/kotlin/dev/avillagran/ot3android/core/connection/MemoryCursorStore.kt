package dev.avillagran.ot3android.core.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [ShellCursorStore] for tests. */
class MemoryCursorStore : ShellCursorStore {
    val cursors = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Every [setCursor] call, in order. */
    val writes = mutableListOf<Pair<String, Long?>>()

    override suspend fun cursor(environmentId: String): Long? = cursors.value[environmentId]

    override suspend fun setCursor(environmentId: String, cursor: Long?) {
        writes += environmentId to cursor
        cursors.update { if (cursor == null) it - environmentId else it + (environmentId to cursor) }
    }
}
