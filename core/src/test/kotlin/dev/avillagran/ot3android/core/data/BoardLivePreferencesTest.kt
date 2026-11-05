package dev.avillagran.ot3android.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.avillagran.ot3android.core.data.prefs.HomePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BoardLivePreferencesTest {
    @get:Rule val files = TemporaryFolder(File("/var/tmp/o3-board-live-prefs").apply { mkdirs() })
    private fun withStore(block: suspend (HomePreferences) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { block(HomePreferences(PreferenceDataStoreFactory.create(scope = scope) { File(files.root, "home.preferences_pb") })) }
        finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun liveModeSurvivesRestartWithoutChangingShelfFolds() {
        withStore { prefs ->
            assertFalse(prefs.boardLive.first())
            prefs.setBoardLive(true)
            prefs.toggleSidebarSection("board:Done")
        }
        withStore { prefs ->
            assertTrue(prefs.boardLive.first())
            assertEquals(setOf("board:Done"), prefs.sidebarCollapsed.first())
            prefs.setBoardLive(false)
        }
        withStore { prefs -> assertFalse(prefs.boardLive.first()) }
    }
}
