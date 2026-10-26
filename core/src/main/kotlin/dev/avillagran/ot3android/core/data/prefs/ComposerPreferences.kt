package dev.avillagran.ot3android.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

private val Context.composerDataStore by preferencesDataStore(name = "composer")

/** Local model/option drafts only. Server favorites remain server-owned; no credentials. */
class ComposerPreferences(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.composerDataStore)

    suspend fun model(environmentId: String, threadId: String): String? = dataStore.data.first()[key(environmentId, threadId)]

    suspend fun saveModel(environmentId: String, threadId: String, selection: String) {
        dataStore.edit { it[key(environmentId, threadId)] = selection }
    }

    private fun key(environmentId: String, threadId: String): Preferences.Key<String> {
        // Length prefix makes identities unambiguous even when either contains separators.
        val identity = "${environmentId.length}:$environmentId$threadId".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(identity).joinToString("") { "%02x".format(it) }
        return stringPreferencesKey("model_$digest")
    }
}
