package dev.avillagran.ot3android.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

    val hostStyles: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        prefs.asMap().entries.filter { it.key.name.startsWith("host_style:") && it.value is String }
            .associate { it.key.name.removePrefix("host_style:") to it.value as String }
    }
    suspend fun setHostStyle(host: String, icon: String, color: String, showName: Boolean) {
        require(icon in setOf("server", "cloud", "linux", "monitor", "laptop", "mac-mini", "mac-studio"))
        require(color in setOf("", "#e06c75", "#e5a050", "#d8c35a", "#8fc46a", "#4fb8a8", "#5aa2e6", "#9a7be6", "#d877b8"))
        dataStore.edit { it[stringPreferencesKey("host_style:$host")] = "$icon|$color|$showName" }
    }

    /** UI-only tree folds and bounded shelves; independent of native membership/filter state. */
    val sidebarCollapsed: Flow<Set<String>> = dataStore.data.map { it[Keys.SIDEBAR_COLLAPSED].orEmpty() }
    val sidebarUnlimited: Flow<Set<String>> = dataStore.data.map { it[Keys.SIDEBAR_UNLIMITED].orEmpty() }
    suspend fun toggleSidebarSection(key: String, limit: Boolean = false) {
        dataStore.edit { prefs ->
            val field = if (limit) Keys.SIDEBAR_UNLIMITED else Keys.SIDEBAR_COLLAPSED
            val current = prefs[field].orEmpty()
            prefs[field] = if (key in current) current - key else current + key
        }
    }

    object Keys {
        val SIDEBAR_COLLAPSED = stringSetPreferencesKey("sidebar_collapsed")
        val SIDEBAR_UNLIMITED = stringSetPreferencesKey("sidebar_unlimited")
        val GROUPED = booleanPreferencesKey("sidebar_grouped")
        val WORKING_EXPANDED = booleanPreferencesKey("working_expanded")
        val SNOOZED_EXPANDED = booleanPreferencesKey("snoozed_expanded")
        val SETTLED_EXPANDED = booleanPreferencesKey("settled_expanded")
    }
}
