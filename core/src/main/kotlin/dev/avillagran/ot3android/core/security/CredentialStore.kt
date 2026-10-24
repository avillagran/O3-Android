@file:Suppress("DEPRECATION")

package dev.avillagran.ot3android.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Credential storage backed by EncryptedSharedPreferences, whose master key lives in the
 * Android Keystore. Tokens are stored per environment id and never logged.
 *
 * androidx.security:security-crypto is deprecated upstream; it stays behind this class so
 * a raw Keystore AES-GCM implementation can replace it without touching callers
 * (see docs/DECISIONS.md).
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun token(environmentId: String): String? = prefs.getString(key(environmentId), null)

    fun setToken(environmentId: String, token: String?) {
        prefs.edit().apply {
            if (token == null) remove(key(environmentId)) else putString(key(environmentId), token)
        }.apply()
    }

    private fun key(environmentId: String) = "token:$environmentId"

    private companion object {
        const val FILE_NAME = "ot3_credentials"
    }
}
