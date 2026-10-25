package dev.avillagran.ot3android.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dev.avillagran.ot3android.core.home.ShelfState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.homeDataStore: DataStore<Preferences> by preferencesDataStore(name = "home")

/** Where Home keeps its grouping and collapsed shelves; tests use an in-memory one. */
interface ShelfStore {
    val shelves: Flow<ShelfState>

    suspend fun update(transform: (ShelfState) -> ShelfState)
}

/** Home's view state that outlives the process: grouping and which shelves are open. */
class HomePreferences(private val dataStore: DataStore<Preferences>) : ShelfStore {

    constructor(context: Context) : this(context.applicationContext.homeDataStore)

    override val shelves: Flow<ShelfState> = dataStore.data.map { it.shelfState() }

    override suspend fun update(transform: (ShelfState) -> ShelfState) {
        dataStore.edit { prefs ->
            val next = transform(prefs.shelfState())
            prefs[Keys.GROUPED] = next.grouped
            prefs[Keys.WORKING_EXPANDED] = next.workingExpanded
            prefs[Keys.SNOOZED_EXPANDED] = next.snoozedExpanded
            prefs[Keys.SETTLED_EXPANDED] = next.settledExpanded
        }
    }

    private fun Preferences.shelfState() = ShelfState(
        grouped = this[Keys.GROUPED] ?: false,
        workingExpanded = this[Keys.WORKING_EXPANDED] ?: false,
        snoozedExpanded = this[Keys.SNOOZED_EXPANDED] ?: false,
        settledExpanded = this[Keys.SETTLED_EXPANDED] ?: false,
    )

    object Keys {
        val GROUPED = booleanPreferencesKey("sidebar_grouped")
        val WORKING_EXPANDED = booleanPreferencesKey("working_expanded")
        val SNOOZED_EXPANDED = booleanPreferencesKey("snoozed_expanded")
        val SETTLED_EXPANDED = booleanPreferencesKey("settled_expanded")
    }
}
