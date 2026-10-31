package dev.avillagran.ot3android.ui.home.layouts

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.Ot3Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Reads existing foreground shell observations. Never creates a connection or thread lease. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun observedSidebarClocks(): Map<String, ReplyClock> {
    val application = LocalContext.current.applicationContext as? Ot3Application ?: return emptyMap()
    val flow = remember(application) {
        application.graph.connectionManager.supervisors.flatMapLatest { supervisors ->
            if (supervisors.isEmpty()) flowOf(emptyMap()) else combine(supervisors.map { (host, supervisor) ->
                supervisor.shell.map { shell -> shell?.threads.orEmpty().associate { thread ->
                    // Desktop src/lib.rs replied_at: completion, else updated/created.
                    val completed = thread.latestRunCompletedAt ?: 0
                    val updated = thread.updatedAt ?: 0
                    val reply = completed.takeIf { it > 0 } ?: maxOf(updated, thread.createdAt ?: 0)
                    "$host/${thread.id}" to ReplyClock(repliedAt = reply, updatedAt = updated,
                        startedAt = thread.latestRunRequestedAt ?: 0)
                } }
            }) { maps -> maps.fold(emptyMap<String, ReplyClock>()) { all, map -> all + map } }
        }
    }
    val clocks by flow.collectAsStateWithLifecycle(emptyMap())
    return clocks
}
