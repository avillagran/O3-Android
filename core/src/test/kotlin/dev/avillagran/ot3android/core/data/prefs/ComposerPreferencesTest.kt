package dev.avillagran.ot3android.core.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComposerPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun selectionsAreScopedByBothIdentitiesWithoutSeparatorCollisions() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("composer.preferences_pb") }
            val preferences = ComposerPreferences(store)
            assertNull(preferences.model("host", "thread"))
            preferences.saveModel("a/b", "c", "personal")
            preferences.saveModel("a", "b/c", "work")
            assertEquals("personal", preferences.model("a/b", "c"))
            assertEquals("work", preferences.model("a", "b/c"))
            assertNull(preferences.model("other", "b/c"))
        } finally { scope.cancel() }
    }

    @Test fun modelAndOptionsSurviveStoreRecreation() = runBlocking {
        val file = folder.root.resolve("composer.preferences_pb")
        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val bytes = """{"instanceId":"work","model":"same","options":[{"id":"effort","value":"high"}]}"""
        val first = ComposerPreferences(PreferenceDataStoreFactory.create(scope = firstScope) { file })
        first.saveModel("node", "thread", bytes)
        firstScope.cancel()
        firstScope.coroutineContext[Job]!!.join()
        val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val second = ComposerPreferences(PreferenceDataStoreFactory.create(scope = secondScope) { file })
            assertEquals(bytes, second.model("node", "thread"))
        } finally { secondScope.cancel() }
    }
}
