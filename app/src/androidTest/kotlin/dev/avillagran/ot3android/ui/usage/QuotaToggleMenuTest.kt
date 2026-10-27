package dev.avillagran.ot3android.ui.usage

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.data.prefs.AppearancePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuotaToggleMenuTest {
    @get:Rule val rule = createComposeRule()
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun close() = owner.cancel()

    @Test fun loadingPreferenceCannotBeMistakenForHiddenUsage() {
        val store = GatedStore()
        val preferences = AppearancePreferences(store)
        rule.setContent { MaterialTheme { QuotaToggleMenuItem(preferences, owner) {} } }
        // No DataStore value has arrived: a default false is not a loaded preference.
        rule.onNodeWithTag("quota_toggle").assertIsNotEnabled()
        store.read.complete(Unit)
        rule.waitUntil { rule.onAllNodesWithText("Hide provider usage").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("quota_toggle").assertIsEnabled().performClick()
        rule.waitUntil { store.writes.value == 1 }
        assertFalse(store.current.value[AppearancePreferences.Keys.PROVIDER_USAGE]!!)
    }

    @Test fun dismissingMenuDoesNotCancelAnAcceptedPreferenceWrite() {
        val store = GatedStore(delayWrite = true).apply { read.complete(Unit) }
        val mounted = mutableStateOf(true)
        rule.setContent { MaterialTheme { if (mounted.value) QuotaToggleMenuItem(AppearancePreferences(store), owner) { mounted.value = false } } }
        rule.waitUntil { rule.onAllNodesWithText("Hide provider usage").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("quota_toggle").performClick()
        rule.waitUntil { store.started.value }
        rule.onNodeWithTag("quota_toggle").assertDoesNotExist()
        store.write.complete(Unit)
        rule.waitUntil { store.writes.value == 1 }
        assertFalse(store.current.value[AppearancePreferences.Keys.PROVIDER_USAGE]!!)
    }

    private class GatedStore(private val delayWrite: Boolean = false) : DataStore<Preferences> {
        val read = CompletableDeferred<Unit>()
        val write = CompletableDeferred<Unit>()
        val started = MutableStateFlow(false)
        val writes = MutableStateFlow(0)
        val current = MutableStateFlow<Preferences>(mutablePreferencesOf(AppearancePreferences.Keys.PROVIDER_USAGE to true))
        override val data: Flow<Preferences> = flow { read.await(); emitAll(current) }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            started.value = true
            // The first test has no need to delay persistence; the dismissal test does.
            if (delayWrite) write.await()
            val changed = transform(current.value)
            current.value = changed
            writes.value++
            return changed
        }
    }
}
