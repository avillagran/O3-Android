package dev.avillagran.ot3android.core.security

/**
 * Secret token per environment id. [CredentialStore] is the real one (Keystore-backed); the
 * interface lets repositories be tested on the JVM. Implementations never log values.
 */
interface TokenStore {
    suspend fun token(environmentId: String): String?
    suspend fun setToken(environmentId: String, token: String)
    suspend fun delete(environmentId: String)
}
