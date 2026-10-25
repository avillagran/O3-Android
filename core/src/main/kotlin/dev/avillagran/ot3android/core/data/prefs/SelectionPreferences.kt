package dev.avillagran.ot3android.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.selectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "selection")

/** Non-secret UI selection state. Credentials never go here. */
class SelectionPreferences(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.selectionDataStore)

    val lastEnvironmentId: Flow<String?> = dataStore.data.map { it[Keys.LAST_ENVIRONMENT_ID] }

    suspend fun setLastEnvironmentId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.LAST_ENVIRONMENT_ID) else prefs[Keys.LAST_ENVIRONMENT_ID] = id
        }
    }

    object Keys {
        val LAST_ENVIRONMENT_ID = stringPreferencesKey("last_environment_id")
    }
}
