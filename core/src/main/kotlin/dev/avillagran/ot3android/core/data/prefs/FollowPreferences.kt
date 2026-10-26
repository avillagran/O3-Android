package dev.avillagran.ot3android.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.followStore by preferencesDataStore("thread_follow")
class FollowPreferences(private val context: Context) {
    private val key = booleanPreferencesKey("enabled")
    val enabled = context.followStore.data.map { it[key] ?: false }
    suspend fun setEnabled(value: Boolean) { context.followStore.edit { it[key] = value } }
}
