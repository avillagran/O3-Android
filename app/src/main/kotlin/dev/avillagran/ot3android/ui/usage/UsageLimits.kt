package dev.avillagran.ot3android.ui.usage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.thread.objects
import org.json.JSONObject
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Locale
import java.util.Date

/** Renders the native public projection as-is: no account pooling or pace calculation. */
fun androidx.compose.foundation.lazy.LazyListScope.usageLimits(state: JSONObject?) {
    item("limits_pool_notice") {
        Text(stringResource(R.string.usage_pool_unavailable), color = Ot3Theme.colors.dim,
            modifier = Modifier.testTag("limits_pool_unavailable"))
    }
    if (state == null) return
    val providers = state.optJSONArray("providers").objects()
    val sources = state.optJSONArray("usageLimitSources").objects()
    if (providers.isEmpty() && sources.isEmpty()) item("limits_empty") { Text(stringResource(R.string.limits_empty), color = Ot3Theme.colors.dim) }
    providers.forEachIndexed { index, provider ->
        item("limits_provider_$index") {
            LimitAccount(provider.optString("displayName").ifBlank { provider.optString("driver") },
                provider.optJSONObject("auth")?.optString("email").orEmpty(),
                provider.optJSONObject("usageLimits"), "provider_${provider.optString("instanceId")}",
                provider.optJSONObject("auth")?.optString("status") == "unauthenticated")
        }
    }
    sources.forEachIndexed { sourceIndex, source ->
        item("limits_source_$sourceIndex") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(source.optString("label"), style = MaterialTheme.typography.titleSmall)
                if (source.has("error")) Text(stringResource(R.string.ot3_usage_unavailable), color = Ot3Theme.colors.failed)
            }
        }
        source.optJSONArray("accounts").objects().forEachIndexed { index, account ->
            item("limits_account_${sourceIndex}_$index") {
                LimitAccount(account.optString("driver"), listOf(account.optString("email"), account.optString("plan")).filter { it.isNotBlank() }.joinToString(" · "), account.optJSONObject("usageLimits"), "account_${source.optString("id")}_${account.optString("id")}")
            }
        }
    }
}

@Composable
private fun LimitAccount(title: String, detail: String, limits: JSONObject?, tag: String, needsAuth: Boolean = false) {
    val locale = LocalConfiguration.current.locales[0]
    Surface(color = Ot3Theme.colors.card, contentColor = Ot3Theme.colors.foreground, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag("limits_$tag")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail.isNotBlank()) Text(detail, color = Ot3Theme.colors.dim, style = MaterialTheme.typography.labelSmall)
            if (needsAuth) Text(stringResource(R.string.usage_auth_required), color = Ot3Theme.colors.failed)
            if (limits == null || limits.has("unavailable")) Text(stringResource(R.string.ot3_usage_unavailable), color = Ot3Theme.colors.dim)
            limits?.takeUnless { it.has("unavailable") || needsAuth }?.optJSONArray("windows").objects().forEach { window ->
                val used = window.optDouble("usedPercent").takeIf { it.isFinite() && it in 0.0..100.0 } ?: return@forEach
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(window.getString("label"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.limits_used, NumberFormat.getPercentInstance(locale).format(used / 100)))
                }
                Box(Modifier.fillMaxWidth().height(6.dp).progressSemantics((used / 100).toFloat()).background(Ot3Theme.colors.divider, RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth((used / 100).toFloat()).fillMaxHeight().background(Ot3Theme.colors.accent, RoundedCornerShape(3.dp)))
                }
                formatLimitTime(window.optString("resetsAt"))?.let { Text(stringResource(R.string.ot3_usage_resets_at, it), style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim) }
            }
            formatLimitTime(limits?.optString("checkedAt").orEmpty())?.let { Text(stringResource(R.string.limits_checked, it), style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim) }
        }
    }
}

@Composable
internal fun formatLimitTime(value: String): String? {
    val locale = LocalConfiguration.current.locales[0]
    return runCatching { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(limitDate(value) ?: return null) }.getOrNull()
}

/** The UI shows minutes, so fractional seconds are omitted before platform parsing. */
internal fun limitDate(value: String): Date? {
    val seconds = value.replace(Regex("\\.\\d+(?=Z|[+-])"), "")
    val cursor = ParsePosition(0)
    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).apply { isLenient = false }.parse(seconds, cursor)
    return date?.takeIf { cursor.index == seconds.length }
}
