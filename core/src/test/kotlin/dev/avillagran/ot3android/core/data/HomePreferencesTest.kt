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

class HomePreferencesTest {

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

    @Test
    fun collapsedShelvesAndGroupingSurviveARestart() {
        val file = folder.root.resolve("home.preferences_pb")
        withStore(file) { prefs ->
            assertEquals("everything off by default, as ot3", ShelfState(), prefs.shelves.first())
            prefs.update { it.copy(grouped = true).withExpanded(Shelf.Settled, true).withExpanded(Shelf.Working, true) }
            prefs.update { it.withExpanded(Shelf.Working, false) }
        }
        withStore(file) { prefs ->
            assertEquals(ShelfState(grouped = true, workingExpanded = false, snoozedExpanded = false, settledExpanded = true), prefs.shelves.first())
        }
    }
}
