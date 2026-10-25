package dev.avillagran.ot3android

import android.content.Context
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ServerProbe
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.local.Ot3Database
import dev.avillagran.ot3android.core.data.prefs.SelectionPreferences
import dev.avillagran.ot3android.core.data.prefs.ShellCursorPreferences
import dev.avillagran.ot3android.core.security.CredentialStore
import dev.avillagran.ot3android.core.transport.NativeT3TransportFactory
import dev.avillagran.ot3android.core.transport.T3TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manual dependency graph, created once by [Ot3Application].
 *
 * Everything is lazy so that cold start only pays for what the first screen touches.
 * See docs/DECISIONS.md for why this is not Hilt. [transportFactory] creates one transport
 * per environment; tests pass a fake one.
 */
class AppGraph(
    context: Context,
    val transportFactory: T3TransportFactory = NativeT3TransportFactory(),
) {
    private val appContext = context.applicationContext

    /** Lives as long as the process; connection supervisors run here. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: Ot3Database by lazy { Ot3Database.create(appContext) }

    val selectionPreferences: SelectionPreferences by lazy { SelectionPreferences(appContext) }

    val shellCursors: ShellCursorPreferences by lazy { ShellCursorPreferences(appContext) }

    private val credentialStoreLock = Mutex()
    private var credentialStoreInstance: CredentialStore? = null

    /** Opened on first use, off the main thread; see [CredentialStore.open]. */
    suspend fun credentialStore(): CredentialStore = credentialStoreLock.withLock {
        credentialStoreInstance ?: CredentialStore.open(appContext).also { credentialStoreInstance = it }
    }

    val environmentRepository: EnvironmentRepository by lazy {
        EnvironmentRepository(database.environmentDao(), { credentialStore() }, shellCursors)
    }

    val connectionManager: ConnectionManager by lazy {
        ConnectionManager(environmentRepository, shellCursors, transportFactory, appScope)
    }

    val serverProbe: ServerProbe by lazy { ServerProbe(transportFactory) }
}
