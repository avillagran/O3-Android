package dev.avillagran.ot3android

import kotlinx.coroutines.flow.asStateFlow

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
import dev.avillagran.ot3android.core.theme.EnvironmentThemeReader
import dev.avillagran.ot3android.core.theme.FileThemeCache
import dev.avillagran.ot3android.core.theme.LiveThemeSource
import dev.avillagran.ot3android.core.theme.ThemePreferences
import dev.avillagran.ot3android.core.thread.DraftRepository
import dev.avillagran.ot3android.core.thread.MessageSender
import dev.avillagran.ot3android.core.thread.OutboxRepository
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.NativeT3TransportFactory
import dev.avillagran.ot3android.core.transport.PairingClient
import dev.avillagran.ot3android.core.transport.T3TransportFactory
import dev.avillagran.ot3android.ui.pair.PairingInbox
import dev.avillagran.ot3android.ui.theme.OmarchyThemeCatalog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.coroutineScope
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
    val transportFactory: T3TransportFactory = T3TransportFactory { ConnectedAppTransport() },
) {
    private val appContext = context.applicationContext
    val gitJournal by lazy { dev.avillagran.ot3android.core.git.GitJournal(appContext.noBackupFilesDir.resolve("git-journal-v1")) }
    val checkoutAdmission by lazy { dev.avillagran.ot3android.core.git.CheckoutAdmission(gitJournal) }
    init {
        // El grafo se crea antes de aceptar selecciones; limpia archivos de procesos interrumpidos.
        appContext.cacheDir.listFiles { file -> file.name.startsWith("composer-") && file.name.endsWith(".upload") }?.forEach { it.delete() }
    }
    private val threadMenuOwners = mutableMapOf<String, Pair<dev.avillagran.ot3android.core.transport.T3Transport, dev.avillagran.ot3android.ui.settings.projects.ThreadMenuOperations>>()
    @Synchronized fun threadMenuOwner(environment: String, thread: String, transport: dev.avillagran.ot3android.core.transport.T3Transport): dev.avillagran.ot3android.ui.settings.projects.ThreadMenuOperations {
        val key = org.json.JSONArray().put(environment).put(thread).toString()
        val previous = threadMenuOwners[key]
        if (previous != null && (previous.first === transport || previous.second.state.value.retryCommandId != null)) return previous.second
        return dev.avillagran.ot3android.ui.settings.projects.ThreadMenuOperations(transport, messageSender).also { threadMenuOwners[key] = transport to it }
    }
    val projectPresentation by lazy { dev.avillagran.ot3android.ui.settings.projects.ProjectPresentation(appContext.getSharedPreferences("project-presentation", Context.MODE_PRIVATE)) }
    private val mutableProjectPresentation by lazy { kotlinx.coroutines.flow.MutableStateFlow(projectPresentation.snapshot().toString()) }
    val projectPresentationState by lazy { mutableProjectPresentation.asStateFlow() }
    fun updateProjectPresentation(change: (dev.avillagran.ot3android.ui.settings.projects.ProjectPresentation) -> Unit) {
        change(projectPresentation)
        mutableProjectPresentation.value = projectPresentation.snapshot().toString()
    }
    private val diffPreferences by lazy { appContext.getSharedPreferences("diff-review", Context.MODE_PRIVATE) }
    private val mutableDiffSettings by lazy { kotlinx.coroutines.flow.MutableStateFlow(dev.avillagran.ot3android.ui.git.DiffReviewSettings(
        diffPreferences.getBoolean("split", false), diffPreferences.getBoolean("wrap", true), diffPreferences.getBoolean("context", true))) }
    val diffReviewSettings by lazy { mutableDiffSettings.asStateFlow() }
    fun setDiffReviewSettings(settings: dev.avillagran.ot3android.ui.git.DiffReviewSettings) {
        check(diffPreferences.edit().putBoolean("split", settings.split).putBoolean("wrap", settings.wordWrap).putBoolean("context", settings.showContext).commit())
        mutableDiffSettings.value = settings
    }
    private val prWriteReceipts by lazy { appContext.getSharedPreferences("pr-write-receipts", Context.MODE_PRIVATE) }
    private fun prWriteKey(route: dev.avillagran.ot3android.ui.navigation.PullRequest): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(org.json.JSONArray().put(route.environmentId).put(route.projectId)
            .put(route.host).put(route.repository).put(route.number).toString().toByteArray()).joinToString("") { "%02x".format(it) }
    fun pullRequestWriteUnresolved(route: dev.avillagran.ot3android.ui.navigation.PullRequest) = prWriteReceipts.getBoolean(prWriteKey(route), false)
    fun setPullRequestWriteUnresolved(route: dev.avillagran.ot3android.ui.navigation.PullRequest, value: Boolean) {
        check(prWriteReceipts.edit().putBoolean(prWriteKey(route), value).commit()) { "pr_receipt_storage_unavailable" }
    }
    private val readerPreferences by lazy { appContext.getSharedPreferences("reader-preferences", Context.MODE_PRIVATE) }
    private val mutableMathRendering by lazy { kotlinx.coroutines.flow.MutableStateFlow(readerPreferences.getBoolean("math", true)) }
    val mathRenderingEnabled by lazy { mutableMathRendering.asStateFlow() }
    fun setMathRenderingEnabled(enabled: Boolean) {
        check(readerPreferences.edit().putBoolean("math", enabled).commit())
        mutableMathRendering.value = enabled
    }
    val generalClientPreferences by lazy { dev.avillagran.ot3android.core.settings.GeneralClientPreferences(appContext) }
    val scopedSettings by lazy { dev.avillagran.ot3android.core.settings.ScopedSettings(connections = dev.avillagran.ot3android.core.settings.SettingsConnections { id -> connectionManager.supervisor(id)?.connection?.value?.transport }) }
    val providerRefreshGate = dev.avillagran.ot3android.ui.usage.ProviderRefreshGate()
    val maintenanceReceipts by lazy { dev.avillagran.ot3android.core.maintenance.FileMaintenanceReceipts(java.io.File(appContext.noBackupFilesDir, "maintenance-receipts")) }
    val applicationContext: Context get() = appContext

    /**
     * Lives as long as the process; connection supervisors run here. A coroutine that fails
     * is logged by its exception class only: messages may carry server data or what the user
     * typed, never a token, but are not logged either way.
     */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error -> Log.e(TAG, "Uncaught ${error.javaClass.name} in appScope") },
    )

    val quotaSource by lazy { dev.avillagran.ot3android.ui.usage.SharedQuotaSource(appScope, connectionManager::connectionFlow, providerRefreshGate::refresh) }

    val followPreferences by lazy { dev.avillagran.ot3android.core.data.prefs.FollowPreferences(appContext) }
    val followVisibility = dev.avillagran.ot3android.notifications.FollowVisibility()

    val database: Ot3Database by lazy { Ot3Database.create(appContext) }

    val selectionPreferences: SelectionPreferences by lazy { SelectionPreferences(appContext) }

    val threadCache by lazy { dev.avillagran.ot3android.core.thread.ThreadCache(appContext.noBackupFilesDir.resolve("thread-cache-v1")) }

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
        EnvironmentRepository(database.environmentDao(), { credentialStore() }, shellCursors, managed = database.bridgeManagedDao())
    }

    val connectionManager: ConnectionManager by lazy {
        ConnectionManager(environmentRepository, shellCursors, transportFactory, appScope)
    }

    /**
     * The live-theme settings over the manual choice. `AppearancePreferences(context)` shares
     * the process-wide `appearance` DataStore, so building it here costs no second store.
     */
    val themePreferences: ThemePreferences by lazy { ThemePreferences(appContext, appearancePreferences) }

    /**
     * The environment's live Omarchy theme (UI_SPEC 3.1.3), started on first use. Following an
     * environment starts its supervisor, so the main environment connects for its theme.
     */
    val liveThemeSource: LiveThemeSource by lazy {
        LiveThemeSource(
            scope = appScope,
            reader = EnvironmentThemeReader(DomainEngine.shared),
            cache = FileThemeCache(appContext.filesDir.resolve("live-theme")),
            settings = themePreferences.settings,
            environments = environmentRepository.environments,
            lastUsedEnvironmentId = selectionPreferences.lastEnvironmentId,
            connections = connectionManager::connectionFlow,
        ).also { it.start() }
    }

    /** Candidate native bridge flow; opened explicitly with an invitation, not by a host label. */
    val bridgeCatalog: dev.avillagran.ot3android.core.bridge.BridgeCatalogController by lazy {
        dev.avillagran.ot3android.core.bridge.BridgeCatalogController(
            database, connectionManager, appScope, { credentialStore() },
            mutations = environmentRepository.mutations,
        )
    }

    suspend fun pairBridge(invitation: org.json.JSONObject): Boolean {
        bridgeCatalog.open(java.io.File(appContext.noBackupFilesDir, "omabridge").absolutePath)
        val approved = bridgeCatalog.pair(invitation)
        if (approved) {
            bridgeCatalog.selectBridge(invitation.getJSONObject("address").getString("id"))
            bridgeCatalog.refresh()
        }
        return approved
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
            connectionManager.startIfCurrent(connectionManager.supervisor(id))
        },
    )

    val outboxRepository: OutboxRepository by lazy { OutboxRepository(database.outboxDao()) }

    val draftRepository: DraftRepository by lazy { DraftRepository(database.draftDao()) }

    /** Sends in [appScope], so a send survives its screen. */
    val messageSender: MessageSender by lazy { MessageSender(outboxRepository, appScope, admission = checkoutAdmission) }

    init {
        // A send cut by the previous process dying has an unknown outcome: offer Resend.
        val startedAt = System.currentTimeMillis()
        appScope.launch(Dispatchers.IO) { outboxRepository.recoverInterrupted(startedAt) }
        appScope.launch { themeCatalog() }
        appScope.launch { threadCache.trim() }
        appScope.launch {
            // Observe already-owned shell state; never open background thread subscriptions.
            connectionManager.supervisors.collectLatest { supervisors ->
                coroutineScope {
                    supervisors.forEach { (id, supervisor) -> launch {
                        supervisor.shell.filterNotNull().collect { shell ->
                            try {
                                val sidebar = dev.avillagran.ot3android.core.shell.SidebarView.build(
                                    DomainEngine.shared, listOf(dev.avillagran.ot3android.core.shell.SidebarEnvironment(id, shell = shell)),
                                    System.currentTimeMillis())
                                threadCache.reconcile(id, sidebar.threads.filterNot { it.settled }.map { it.threadId }.toSet())
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { /* An unavailable view must not erase cached content. */ }
                        }
                    } }
                }
            }
        }
        appScope.launch(Dispatchers.IO) {
            val state = java.io.File(appContext.noBackupFilesDir, "omabridge")
            if (java.io.File(state, "mobile-selected-bridge.json").exists() &&
                dev.avillagran.ot3android.core.bridge.NativeBridge.isAvailable()) {
                try { bridgeCatalog.open(state.absolutePath) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep saved environments; show offline on next explicit retry. */ }
            }
        }
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

/** One handle per supervisor: binary uploads use the same authenticated session as RPCs. */
class ConnectedAppTransport private constructor(
    val nativeClient: dev.avillagran.t3.T3Client,
    private val delegate: dev.avillagran.ot3android.core.transport.NativeT3Transport,
) : dev.avillagran.ot3android.core.transport.T3Transport by delegate,
    dev.avillagran.ot3android.ui.thread.composer.ComposerBinaryUpload {
    constructor(client: dev.avillagran.t3.T3Client = dev.avillagran.t3.T3Client()) : this(client, dev.avillagran.ot3android.core.transport.NativeT3Transport(client))
    override suspend fun upload(fd: Int, name: String, mimeType: String, sizeBytes: Long): org.json.JSONObject =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { nativeClient.upload(fd, name, mimeType, sizeBytes) }
}
