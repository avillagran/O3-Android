@file:Suppress("DEPRECATION")

package dev.avillagran.ot3android.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

/**
 * Credential storage backed by EncryptedSharedPreferences, whose master key lives in the
 * Android Keystore. Tokens are stored per environment id and never logged.
 *
 * The master key does not require user authentication: the follow service reads tokens
 * while the device is locked (see docs/DECISIONS.md).
 *
 * androidx.security:security-crypto is deprecated upstream; it stays behind this class so
 * a raw Keystore AES-GCM implementation can replace it without touching callers.
 *
 * Open it with [open]; every call runs on [Dispatchers.IO].
 */
class CredentialStore private constructor(
    private val prefs: SharedPreferences,
    /**
     * True when the encrypted store could not be opened (for example after a Keystore
     * reset or a restore from another device) and was wiped and recreated. Stored tokens
     * are gone; the UI should tell the user to pair again.
     */
    val wasReset: Boolean,
) {

    suspend fun token(environmentId: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(key(environmentId), null)
    }

    suspend fun setToken(environmentId: String, token: String) = withContext(Dispatchers.IO) {
        prefs.edit(commit = true) { putString(key(environmentId), token) }
    }

    /**
     * Removes the token of [environmentId].
     *
     * The environment repository (connection PR) deletes the Room row and calls this in
     * the same operation, so a removed environment never leaves its token behind.
     */
    suspend fun delete(environmentId: String) = withContext(Dispatchers.IO) {
        prefs.edit(commit = true) { remove(key(environmentId)) }
    }

    private fun key(environmentId: String) = "token:$environmentId"

    companion object {
        internal const val FILE_NAME = "ot3_credentials"
        internal const val MASTER_KEY_ALIAS = "ot3_credentials_master_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /**
         * Opens the store off the main thread. If the encrypted file or its master key is
         * unusable, both are deleted and the store is recreated once, with [wasReset] set.
         */
        suspend fun open(context: Context): CredentialStore = withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val opened = openWithOneReset(
                create = { createPrefs(appContext) },
                reset = { wipe(appContext) },
            )
            CredentialStore(opened.value, opened.wasReset)
        }

        private fun createPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setUserAuthenticationRequired(false)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun wipe(context: Context) {
            context.deleteSharedPreferences(FILE_NAME)
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
                deleteEntry(MASTER_KEY_ALIAS)
            }
        }
    }
}

internal class Opened<T>(val value: T, val wasReset: Boolean)

/**
 * Runs [create]; on a crypto or I/O failure runs [reset] and tries [create] exactly once
 * more. A second failure propagates. Exceptions are not logged: they may carry key material
 * or file contents.
 */
internal inline fun <T> openWithOneReset(create: () -> T, reset: () -> Unit): Opened<T> {
    val first = try {
        create()
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IOException) {
        null
    }
    if (first != null) return Opened(first, wasReset = false)
    reset()
    return Opened(create(), wasReset = true)
}
