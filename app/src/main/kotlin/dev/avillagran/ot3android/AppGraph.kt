package dev.avillagran.ot3android

import android.content.Context
import android.util.Log
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ServerProbe
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.local.Ot3Database
import dev.avillagran.ot3android.core.data.prefs.SelectionPreferences
import dev.avillagran.ot3android.core.data.prefs.ShellCursorPreferences
import dev.avillagran.ot3android.core.security.CredentialStore
import dev.avillagran.ot3android.core.thread.DraftRepository
import dev.avillagran.ot3android.core.thread.MessageSender
import dev.avillagran.ot3android.core.thread.OutboxRepository
import dev.avillagran.ot3android.core.transport.NativeT3TransportFactory
import dev.avillagran.ot3android.core.transport.T3TransportFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /**
     * Lives as long as the process; connection supervisors run here. A coroutine that fails
     * is logged by its exception class only: messages may carry server data or what the user
     * typed, never a token, but are not logged either way.
     */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error -> Log.e(TAG, "Uncaught ${error.javaClass.name} in appScope") },
    )

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

    val outboxRepository: OutboxRepository by lazy { OutboxRepository(database.outboxDao()) }

    val draftRepository: DraftRepository by lazy { DraftRepository(database.draftDao()) }

    /** Sends in [appScope], so a send survives its screen. */
    val messageSender: MessageSender by lazy { MessageSender(outboxRepository, appScope) }

    init {
        // A send cut by the previous process dying has an unknown outcome: offer Resend.
        val startedAt = System.currentTimeMillis()
        appScope.launch(Dispatchers.IO) { outboxRepository.recoverInterrupted(startedAt) }
    }

    private companion object {
        const val TAG = "Ot3AppGraph"
    }
}
