package dev.avillagran.ot3android.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.avillagran.ot3android.core.data.prefs.HomePreferences
import dev.avillagran.ot3android.core.home.Shelf
import dev.avillagran.ot3android.core.home.ShelfState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeSidebarPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A DataStore on [file] in its own scope, as one process would hold it. */
    private fun <T> withStore(file: java.io.File, block: suspend (HomePreferences) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            runBlocking { block(HomePreferences(PreferenceDataStoreFactory.create(scope = scope) { file })) }
        } finally {
            // Wait until the store has released the file, as a process exit would.
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun treeLimitsAndHostStyleSurviveRestartWithoutChangingNativeShelves() {
        val file = folder.root.resolve("sidebar.preferences_pb")
        withStore(file) { prefs ->
            prefs.toggleSidebarSection("project:node/app")
            prefs.toggleSidebarSection("waiting", limit = true)
            prefs.setHostStyle("node", "laptop", "#5aa2e6", true)
            prefs.setHostStyle("mac", "server", "#e06c75", false)
        }
        withStore(file) { prefs ->
            assertEquals(setOf("project:node/app"), prefs.sidebarCollapsed.first())
            assertEquals(setOf("waiting"), prefs.sidebarUnlimited.first())
            assertEquals(mapOf("node" to "laptop|#5aa2e6|true", "mac" to "server|#e06c75|false"), prefs.hostStyles.first())
            assertEquals(ShelfState(), prefs.shelves.first())
            prefs.toggleSidebarSection("waiting", limit = true)
            assertEquals(emptySet<String>(), prefs.sidebarUnlimited.first())
        }
    }
}
