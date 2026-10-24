package dev.avillagran.ot3android

import android.content.Context
import dev.avillagran.ot3android.core.data.local.Ot3Database
import dev.avillagran.ot3android.core.data.prefs.SelectionPreferences
import dev.avillagran.ot3android.core.security.CredentialStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manual dependency graph, created once by [Ot3Application].
 *
 * Everything is lazy so that cold start only pays for what the first screen touches.
 * See docs/DECISIONS.md for why this is not Hilt.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val database: Ot3Database by lazy { Ot3Database.create(appContext) }

    val selectionPreferences: SelectionPreferences by lazy { SelectionPreferences(appContext) }

    private val credentialStoreLock = Mutex()
    private var credentialStoreInstance: CredentialStore? = null

    /** Opened on first use, off the main thread; see [CredentialStore.open]. */
    suspend fun credentialStore(): CredentialStore = credentialStoreLock.withLock {
        credentialStoreInstance ?: CredentialStore.open(appContext).also { credentialStoreInstance = it }
    }
}
