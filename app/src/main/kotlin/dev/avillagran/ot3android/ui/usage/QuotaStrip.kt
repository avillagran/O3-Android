package dev.avillagran.ot3android.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.core.data.prefs.AppearanceSettings
import dev.avillagran.ot3android.ui.navigation.VerticalReveal
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.thread.objects
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.NumberFormat

/** This slot owns only quota observations; it never replaces the reader or its state. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun QuotaStripHost(environmentId: String? = null, compact: Boolean = true,
    source: SharedQuotaSource? = null, environments: Flow<List<dev.avillagran.ot3android.core.domain.Environment>>? = null, onOpenLimits: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val settings by graph.appearancePreferences.settings.collectAsStateWithLifecycle(AppearanceSettings())
    val quotaSource = source ?: graph.quotaSource
    val environmentFlow = environments ?: graph.environmentRepository.environments
    val observations = remember(settings.providerUsage, environmentId, quotaSource, environmentFlow) {
        if (!settings.providerUsage) flowOf(emptyList()) else environmentFlow.flatMapLatest { all ->
            val selected = all.filter { environmentId == null || it.id == environmentId }
            if (selected.isEmpty()) flowOf(emptyList()) else combine(selected.map { env ->
                quotaSource.observe(env.id).map { env.label to it }
            }) { it.toList() }
        }
    }
    val states by observations.collectAsStateWithLifecycle(emptyList())
    // Keep the departing content measurable during Ot3Reveal; no departing subscription.
    var last by remember { mutableStateOf<List<Pair<String, QuotaState>>>(emptyList()) }
    if (settings.providerUsage) last = states
    QuotaStrip(if (settings.providerUsage) states else last, settings.providerUsage, compact, onOpenLimits)
}

@Composable
fun QuotaToggleMenuItem(onDismiss: () -> Unit = {}) {
    val graph = LocalContext.current.appGraph
    QuotaToggleMenuItem(graph.appearancePreferences, graph.appScope, onDismiss)
}

@Composable
internal fun QuotaToggleMenuItem(
    preferences: dev.avillagran.ot3android.core.data.prefs.AppearancePreferences,
    writeScope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
) {
    val settings by preferences.settings.collectAsStateWithLifecycle<AppearanceSettings?>(null)
    DropdownMenuItem(
        text = { Text(stringResource(when (settings?.providerUsage) {
            true -> R.string.ot3_home_hide_provider_usage
            false -> R.string.ot3_home_show_provider_usage
            null -> R.string.ot3_loading
        })) },
        enabled = settings != null,
        onClick = {
            // The popup is dismissed immediately; its composition cannot own persistence.
            writeScope.launch { preferences.toggleProviderUsage() }
            onDismiss()
        }, modifier = Modifier.testTag("quota_toggle"))
}

@Composable
fun QuotaStrip(states: List<Pair<String, QuotaState>>, enabled: Boolean, compact: Boolean, onOpenLimits: () -> Unit) {
    val groups = states.flatMap { (host, state) ->
        val limits = state.limits
        val providers = limits?.optJSONArray("providers").objects().filter { it.optJSONObject("auth")?.optString("status") != "unauthenticated" }.map { provider ->
            Triple(host, provider.optString("displayName").ifBlank { quotaProviderLabel(provider.optString("driver")) }, provider.optJSONObject("usageLimits"))
        }
        providers + limits?.optJSONArray("usageLimitSources").objects().flatMap { source ->
            source.optJSONArray("accounts").objects().map { account -> Triple(host, quotaProviderLabel(account.optString("driver")), account.optJSONObject("usageLimits")) }
        }
    }.filter { it.third?.has("unavailable") != true && it.third?.optJSONArray("windows")?.length()?.let { n -> n > 0 } == true }
    val authHosts = states.filter { (_, state) -> state.limits?.optJSONArray("providers").objects()
        .any { it.optJSONObject("auth")?.optString("status") == "unauthenticated" } }
    val unavailableHosts = states.filter { (_, state) ->
        val providers = state.limits?.optJSONArray("providers").objects()
        val sources = state.limits?.optJSONArray("usageLimitSources").objects()
        providers.any { it.optJSONObject("usageLimits")?.has("unavailable") == true } ||
            sources.any { source -> source.has("error") || source.optJSONArray("accounts").objects()
                .any { it.optJSONObject("usageLimits")?.has("unavailable") == true } }
    }
    val notices = states.filter { it.second.offline || it.second.failed || it.second.busy }
    VerticalReveal(enabled && (groups.isNotEmpty() || notices.isNotEmpty() || authHosts.isNotEmpty() || unavailableHosts.isNotEmpty()), Modifier.fillMaxWidth().testTag("quota_reveal")) {
        val scroll = if (compact) Modifier else Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())
        Column(scroll.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("quota_strip"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            notices.forEach { (host, state) ->
                Text("$host · " + stringResource(when { state.offline -> R.string.ot3_usage_offline; state.failed -> R.string.ot3_usage_unavailable; else -> R.string.mobile_connecting_environment }), style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim)
            }
            unavailableHosts.forEach { (host, _) ->
                Text("$host · " + stringResource(R.string.ot3_usage_unavailable), style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim)
            }
            authHosts.forEach { (host, _) ->
                Text("$host · " + stringResource(R.string.usage_auth_required), style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim)
            }
            groups.forEach { (host, title, limits) ->
                Surface(onClick = onOpenLimits, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("quota_group_${host}_$title"),
                    color = Ot3Theme.colors.foreground.copy(alpha = .025f), contentColor = Ot3Theme.colors.foreground,
                    shape = RoundedCornerShape(4.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Ot3Theme.colors.foreground.copy(alpha = .09f))) {
                    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("$title · $host ›", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val windows = limits?.optJSONArray("windows").objects()
                        (if (compact) windows.take(2) else windows).forEach { window -> CompactWindow(window) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactWindow(window: JSONObject) {
    val used = (window.getDouble("usedPercent") / 100).toFloat().coerceIn(0f, 1f)
    val locale = LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(window.getString("label"), Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).height(6.dp).progressSemantics(used).background(Ot3Theme.colors.foreground.copy(alpha = .12f), RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(used).fillMaxHeight().background(Ot3Theme.colors.accent, RoundedCornerShape(2.dp)))
        }
        Text(stringResource(R.string.limits_used, NumberFormat.getPercentInstance(locale).format(window.getDouble("usedPercent") / 100)), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        formatLimitTime(window.optString("resetsAt"))?.let {
            Text(stringResource(R.string.ot3_usage_resets_at, it), Modifier.widthIn(max = 96.dp), style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Display names only; no native quota/account policy is duplicated here. */
private fun quotaProviderLabel(driver: String): String = when (driver) {
    "codex" -> "Codex"
    "claudeAgent", "claudeCode", "claude" -> "Claude"
    "opencode" -> "OpenCode Go"
    "cursor" -> "Cursor"
    "antigravity" -> "Antigravity"
    "kimi-coding" -> "Kimi"
    "nous" -> "Nous"
    "grok" -> "Grok"
    "pi" -> "Pi"
    else -> driver
}
