package dev.avillagran.ot3android.core.theme

import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The theme the app wears (UI_SPEC 3.1.3), as [selection].
 *
 * **Which environment:** the one on screen ([showOnScreen], a thread or a one-environment
 * Home); otherwise (the merged Home) the main one: the one chosen in
 * Settings, else the last used, else the last connected.
 *
 * **When it reads** (each a full [EnvironmentThemeReader.read]): when that environment's
 * supervisor connects (every new [Connection], so after a reconnect too), when the app comes
 * back to the foreground ([setVisible]), when the environment changes, and on [refresh]. While
 * visible and connected it polls `theme.name` every [pollIntervalMs] (one call; T3 sends no
 * event for `omarchy-theme-set`) and reads the whole theme when the name changed. Nothing runs
 * while the app is in the background.
 *
 * **Cache:** the last theme of each environment ([ThemeCache]) is shown at once
 * ([ThemeSelection.Live.fromCache]) until the environment is read. A read that fails with an
 * error keeps the theme shown (a dropped connection must not flip the colours); one that finds
 * no Omarchy or no `filesystem:read` drops the cache and falls back to the manual choice.
 */
class LiveThemeSource(
    private val scope: CoroutineScope,
    private val reader: EnvironmentThemeReader,
    private val cache: ThemeCache,
    private val settings: Flow<ThemeSettings>,
    private val environments: Flow<List<Environment>>,
    private val lastUsedEnvironmentId: Flow<String?>,
    /** The supervisor's [Connection] of a local environment id (null while not connected). */
    private val connections: (environmentId: String) -> Flow<Connection?>,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {
    /** Screens showing one environment, oldest first; the last one is on screen. */
    private val screens = MutableStateFlow<List<Pair<Any, String>>>(emptyList())
    private val visible = MutableStateFlow(false)
    private val refreshes = Channel<Unit>(Channel.CONFLATED)

    private val _selection = MutableStateFlow<ThemeSelection>(ThemeSelection.Fallback(FallbackReason.Loading))

    /** [FallbackReason.Loading] until the settings and the cache have been read. */
    val selection: StateFlow<ThemeSelection> = _selection.asStateFlow()

    /** Where each environment's theme files were found; kept across environment switches. */
    private val locations = mutableMapOf<String, ThemeLocation>()

    private var job: Job? = null

    /** Starts following; idempotent. Runs until [scope] ends. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /**
     * A screen showing [environmentId] (a thread, a one-environment Home) came up; close the
     * result when it goes. The latest open claim wins, so a screen entering before the previous
     * one leaves (a navigation transition) never leaves nothing on screen; with no claim, the
     * merged Home's main environment applies.
     */
    fun showOnScreen(environmentId: String): AutoCloseable {
        val claim = Any()
        screens.update { it + (claim to environmentId) }
        return AutoCloseable { screens.update { list -> list.filterNot { it.first === claim } } }
    }

    /** The app is visible (process ON_START) or not (ON_STOP); coming back reads the theme again. */
    fun setVisible(visible: Boolean) {
        this.visible.value = visible
    }

    /** Reads the followed environment's theme now, if it is connected and the app visible. */
    fun refresh() {
        refreshes.trySend(Unit)
    }

    private suspend fun run() {
        combine(settings, environments, lastUsedEnvironmentId, screens) { settings, all, lastUsed, screens ->
            target(settings, all, lastUsed, screens.lastOrNull()?.second)
        }.distinctUntilChanged().collectLatest { target ->
            when (target) {
                Target.Off -> publish(ThemeSelection.Fallback(FallbackReason.FollowingOff))
                Target.None -> publish(ThemeSelection.Fallback(FallbackReason.NoEnvironment))
                is Target.Follow -> follow(target.environmentId, target.label)
            }
        }
    }

    private fun target(settings: ThemeSettings, all: List<Environment>, lastUsed: String?, screen: String?): Target {
        if (!settings.followEnvironment) return Target.Off
        val byId = all.associateBy { it.id }
        val environment = screen?.let(byId::get)
            ?: settings.mainEnvironmentId?.let(byId::get)
            ?: lastUsed?.let(byId::get)
            ?: all.maxByOrNull { it.lastConnectedAt ?: Long.MIN_VALUE }
        return environment?.let { Target.Follow(it.id, it.label) } ?: Target.None
    }

    /** Follows one environment until the target changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun follow(environmentId: String, label: String) {
        val follower = Follower(environmentId, label)
        follower.showCached()
        visible.flatMapLatest { shown -> if (shown) connections(environmentId) else flowOf(null) }
            .distinctUntilChanged()
            .collectLatest { connection ->
                if (connection == null) return@collectLatest
                while (refreshes.tryReceive().isSuccess) Unit
                follower.read(connection.transport)
                while (true) {
                    val asked = withTimeoutOrNull(pollIntervalMs) { refreshes.receive() } != null
                    if (asked) follower.read(connection.transport) else follower.poll(connection.transport)
                }
            }
    }

    private inner class Follower(private val environmentId: String, private val label: String) {
        /** The live theme shown for this environment, from the cache or a read. */
        private var shown: ThemeSelection.Live? = null

        /** `theme.name` as last read, what a poll compares with. */
        private var name: String? = null

        /** The last read failed with an error (not "not Omarchy" or "no scope"): polls retry it. */
        private var failed = false

        suspend fun showCached() {
            shown = cache.read(environmentId)?.let { cached ->
                runCatching {
                    ThemeSelection.Live(
                        environmentId, label, cached.themeName, cached.displayName, cached.colors,
                        JSONObject(cached.tokens), fromCache = true,
                    )
                }.getOrNull()
            }
            name = shown?.themeName
            publish(shown ?: ThemeSelection.Fallback(FallbackReason.Waiting(environmentId, label)))
        }

        suspend fun read(transport: T3Transport) {
            when (val read = reader.read(transport, locations[environmentId])) {
                is ThemeRead.Live -> {
                    failed = false
                    locations[environmentId] = read.location
                    name = read.nameFile
                    val live = ThemeSelection.Live(
                        environmentId, label, read.themeName, read.displayName, read.colors, read.tokens, fromCache = false,
                    )
                    val previous = shown
                    if (previous == null || previous.colors != live.colors || previous.themeName != live.themeName) {
                        cache.write(
                            environmentId,
                            CachedTheme(live.themeName, live.displayName, live.colors, live.tokens.toString()),
                        )
                    }
                    shown = live
                    publish(live)
                }
                is ThemeRead.Unavailable -> {
                    locations.remove(environmentId)
                    val reason = read.reason
                    failed = reason is ThemeUnavailable.Error
                    val keep = shown
                    if (failed && keep != null) {
                        publish(keep)
                    } else {
                        if (shown != null) cache.remove(environmentId)
                        shown = null
                        name = null
                        publish(ThemeSelection.Fallback(FallbackReason.Unavailable(environmentId, label, reason)))
                    }
                }
            }
        }

        suspend fun poll(transport: T3Transport) {
            val location = locations[environmentId]
            if (location == null) {
                // Never found: retry an error, but do not keep probing a host without Omarchy.
                if (failed) read(transport)
                return
            }
            when (val polled = reader.readName(transport, location)) {
                is EnvironmentThemeReader.NameRead.Name -> if (polled.name != name) read(transport)
                is EnvironmentThemeReader.NameRead.Failed -> Unit
            }
        }
    }

    private fun publish(selection: ThemeSelection) {
        _selection.value = selection
    }

    private sealed interface Target {
        data object Off : Target
        data object None : Target
        data class Follow(val environmentId: String, val label: String) : Target
    }

    companion object {
        /** How often `theme.name` is polled while visible (UI_SPEC 3.1.3: every few minutes). */
        const val POLL_INTERVAL_MS = 3 * 60_000L
    }
}
