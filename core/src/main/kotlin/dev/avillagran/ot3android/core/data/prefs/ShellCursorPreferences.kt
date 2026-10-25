package dev.avillagran.ot3android.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.avillagran.ot3android.core.connection.ShellCursorStore
import kotlinx.coroutines.flow.first

private val Context.connectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "connection")

/** [ShellCursorStore] in DataStore Preferences, one `shell_cursor:<id>` key per environment. */
class ShellCursorPreferences(private val dataStore: DataStore<Preferences>) : ShellCursorStore {

    constructor(context: Context) : this(context.applicationContext.connectionDataStore)

    override suspend fun cursor(environmentId: String): Long? = dataStore.data.first()[key(environmentId)]

    override suspend fun setCursor(environmentId: String, cursor: Long?) {
        dataStore.edit { prefs ->
            if (cursor == null) prefs.remove(key(environmentId)) else prefs[key(environmentId)] = cursor
        }
    }

    private fun key(environmentId: String) = longPreferencesKey("shell_cursor:$environmentId")
}
