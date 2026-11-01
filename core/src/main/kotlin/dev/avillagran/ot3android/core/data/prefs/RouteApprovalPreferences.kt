package dev.avillagran.ot3android.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.avillagran.ot3android.core.connection.RouteApprovalStore
import kotlinx.coroutines.flow.first

private val Context.routeApprovalStore by preferencesDataStore("route_approvals")

/** One key per row. The app excludes all of its data from backup, so this stays on the device. */
class RouteApprovalPreferences(private val store: DataStore<Preferences>) : RouteApprovalStore {
    constructor(context: Context) : this(context.applicationContext.routeApprovalStore)

    override suspend fun all(): Map<String, String> = store.data.first().asMap().entries
        .mapNotNull { (key, value) -> (value as? String)?.let { key.name to it } }.toMap()

    override suspend fun set(environmentId: String, origin: String?) {
        val key = stringPreferencesKey(environmentId)
        store.edit { if (origin == null) it.remove(key) else it[key] = origin }
    }
}
