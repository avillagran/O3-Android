package dev.avillagran.ot3android

import android.content.Context
import android.os.Build
import android.util.Log
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ServerProbe
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.local.Ot3Database
import dev.avillagran.ot3android.core.data.prefs.AppearancePreferences
import dev.avillagran.ot3android.core.data.prefs.HomePreferences
import dev.avillagran.ot3android.core.data.prefs.SelectionPreferences
import dev.avillagran.ot3android.core.data.prefs.ShellCursorPreferences
import dev.avillagran.ot3android.core.pairing.PairingService
import dev.avillagran.ot3android.core.security.CredentialStore
import dev.avillagran.ot3android.core.thread.DraftRepository
import dev.avillagran.ot3android.core.thread.MessageSender
import dev.avillagran.ot3android.core.thread.OutboxRepository
import dev.avillagran.ot3android.core.transport.NativeT3TransportFactory
import dev.avillagran.ot3android.core.transport.PairingClient
import dev.avillagran.ot3android.core.transport.T3TransportFactory
import dev.avillagran.ot3android.ui.pair.PairingInbox
import dev.avillagran.ot3android.ui.theme.OmarchyThemeCatalog
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

    val composerPreferences by lazy { dev.avillagran.ot3android.core.data.prefs.ComposerPreferences(appContext) }

    val shellCursors: ShellCursorPreferences by lazy { ShellCursorPreferences(appContext) }

    val appearancePreferences: AppearancePreferences by lazy { AppearancePreferences(appContext) }

    /** Home's grouping and collapsed shelves. */
    val homePreferences: HomePreferences by lazy { HomePreferences(appContext) }

    /** The bundled Omarchy themes, parsed once off the main thread (started in [init]). */
    suspend fun themeCatalog(): OmarchyThemeCatalog = OmarchyThemeCatalog.load(appContext)

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

    /** Pairing links from deep links and the QR scanner, in memory only. */
    val pairingInbox = PairingInbox()

    /**
     * A pairing flow (one per screen). A saved environment's supervisor is stopped before its
     * URL or token changes; once paired, any supervisor built in between is dropped too and a
     * fresh one starts with the new settings.
     */
    fun pairingService(): PairingService = PairingService(
        transportFactory = transportFactory,
        repository = environmentRepository,
        client = PairingClient(label = pairingLabel()),
        stopConnection = connectionManager::forget,
        handOver = { id ->
            connectionManager.forget(id)
            connectionManager.supervisor(id)?.start()
        },
    )

    val outboxRepository: OutboxRepository by lazy { OutboxRepository(database.outboxDao()) }

    val draftRepository: DraftRepository by lazy { DraftRepository(database.draftDao()) }

    /** Sends in [appScope], so a send survives its screen. */
    val messageSender: MessageSender by lazy { MessageSender(outboxRepository, appScope) }

    init {
        // A send cut by the previous process dying has an unknown outcome: offer Resend.
        val startedAt = System.currentTimeMillis()
        appScope.launch(Dispatchers.IO) { outboxRepository.recoverInterrupted(startedAt) }
        appScope.launch { themeCatalog() }
    }

    private companion object {
        const val TAG = "Ot3AppGraph"

        /** How this device shows in T3's session list: "ot3-Android · <model>". */
        fun pairingLabel(): String {
            val model = Build.MODEL.orEmpty().filterNot(Char::isISOControl).trim().take(64)
            return if (model.isEmpty()) "ot3-Android" else "ot3-Android · $model"
        }
    }
}
