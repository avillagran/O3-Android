package dev.avillagran.ot3android.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.thread.objects
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Currency

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(state: UsageState, onEnvironment: (String) -> Unit, onPeriod: (String) -> Unit, onRefresh: () -> Unit, onBack: () -> Unit, onRefreshPrices: () -> Unit = {}, onTab: (String) -> Unit = {}) {
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var metric by rememberSaveable { mutableStateOf("cost") }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.ot3_usage_title)) }, navigationIcon = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }, actions = {
            TextButton(onClick = onRefresh, enabled = !state.busy && state.environmentId != null, modifier = Modifier.testTag("usage_refresh")) {
                Text(stringResource(R.string.ot3_usage_refresh))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp).testTag("usage_page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("usage" to R.string.ot3_usage_title, "limits" to R.string.ot3_usage_limits).forEach { (id, label) ->
                        FilterChip(selected = state.tab == id, onClick = { onTab(id) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("usage_tab_$id"))
                    }
                    if (landscape && state.tab == "usage") UsagePeriods(state.period, onPeriod, Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    UsageEnvironment(state, onEnvironment)
                }
                if (!landscape && state.tab == "usage") UsagePeriods(state.period, onPeriod)
            }
            when {
                state.busy -> item { Text(stringResource(R.string.ot3_usage_loading)) }
                state.offline -> item { Text(stringResource(R.string.ot3_usage_offline)) }
                state.failed -> item { Text(stringResource(R.string.ot3_usage_unavailable), color = Ot3Theme.colors.failed) }
            }
            if (state.tab == "limits") usageLimits(state.limits)
            state.summary?.takeIf { state.tab == "usage" }?.let { summary ->
                item { UsageTotals(summary, landscape) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(if (metric == "cost") R.string.ot3_usage_cost_day else R.string.ot3_usage_tokens_day), modifier = Modifier.weight(1f))
                        listOf("cost" to R.string.ot3_usage_cost, "tokens" to R.string.ot3_usage_tokens).forEach { (id, label) ->
                            FilterChip(selected = metric == id, onClick = { metric = id }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("usage_metric_$id"))
                        }
                    }
                    UsageDays(summary, metric)
                }
                item { Text(stringResource(R.string.ot3_usage_models), style = MaterialTheme.typography.titleMedium) }
                val models = summary.optJSONArray("models").objects()
                val modelPeak = models.maxOfOrNull { it.optDouble(metric, 0.0) } ?: 0.0
                models.forEachIndexed { index, model ->
                    item("model_$index") {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(model.optString("model"), style = MaterialTheme.typography.bodyMedium)
                                Text(model.optString("provider"), color = Ot3Theme.colors.dim, style = MaterialTheme.typography.labelSmall)
                                val fraction = if (modelPeak > 0) (model.optDouble(metric, 0.0) / modelPeak).toFloat() else 0f
                                Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(Ot3Theme.colors.accent.copy(alpha = .6f), RoundedCornerShape(3.dp)))
                            }
                            Text(usageNumber(model.optDouble(metric), metric == "cost"))
                        }
                    }
                }
                if (summary.optLong("unpricedRecords") > 0 || summary.optString("pricing") != "fresh") item {
                    TextButton(onClick = onRefreshPrices, enabled = !state.busy && !state.offline, modifier = Modifier.testTag("usage_refresh_prices")) {
                        Text(stringResource(R.string.ot3_usage_refresh_prices))
                    }
                }
                if (summary.optLong("unpricedRecords") > 0) item { Text(stringResource(R.string.ot3_usage_unpriced, summary.optLong("unpricedRecords").toString())) }
                if (summary.optString("pricing") == "unavailable") item { Text(stringResource(R.string.ot3_usage_prices_unavailable), color = Ot3Theme.colors.dim) }
                summary.optJSONArray("sources").objects().forEachIndexed { index, source ->
                    item("source_$index") { Text(listOf(source.optString("provider"), source.optString("status"), source.optString("message")).filter { it.isNotBlank() && it != "null" }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim) }
                }
            }
        }
    }
}

@Composable
private fun UsageEnvironment(state: UsageState, onEnvironment: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("usage_environment")) {
            Text(state.environments.firstOrNull { it.id == state.environmentId }?.label ?: stringResource(R.string.environments_empty))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.environments.forEach { environment ->
                DropdownMenuItem(text = { Text(environment.label) }, onClick = { onEnvironment(environment.id); open = false })
            }
        }
    }
}

@Composable
private fun UsagePeriods(period: String, onPeriod: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("day" to R.string.ot3_usage_day, "week" to R.string.ot3_usage_week, "month" to R.string.ot3_usage_month, "quarter" to R.string.ot3_usage_quarter).forEach { (id, label) ->
            FilterChip(selected = period == id, onClick = { onPeriod(id) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("usage_period_$id"))
        }
    }
}

@Composable
private fun UsageTotals(summary: JSONObject, landscape: Boolean) {
    val fields = listOf("cost" to R.string.ot3_usage_cost, "tokens" to R.string.ot3_usage_tokens, "sessions" to R.string.ot3_usage_sessions, "cacheSavings" to R.string.ot3_usage_cache)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.chunked(if (landscape) 4 else 2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (field, label) ->
                    Surface(Modifier.weight(1f), color = Ot3Theme.colors.card, contentColor = Ot3Theme.colors.foreground, shape = MaterialTheme.shapes.small) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim)
                            Text(usageNumber(summary.optDouble(field), field == "cost" || field == "cacheSavings"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("usage_total_$field"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageDays(summary: JSONObject, metric: String) {
    val days = summary.optJSONArray("days").objects()
    val peak = days.maxOfOrNull { it.optDouble(metric, 0.0) } ?: 0.0
    var selected by rememberSaveable(summary.optString("sinceDay"), summary.optString("untilDay")) { mutableIntStateOf(-1) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(150.dp), verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { index, day ->
            val label = "${day.optString("day")} · ${usageNumber(day.optDouble("cost"), true)} · ${usageNumber(day.optDouble("tokens"), false)}"
            Column(Modifier.width(48.dp).fillMaxHeight().clickable { selected = index }.semantics { contentDescription = label }.testTag("usage_day_$index"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                val height = if (peak > 0) (124 * day.optDouble(metric, 0.0) / peak).toFloat().let { if (it > 0) it.coerceAtLeast(2f) else 0f } else 0f
                Box(Modifier.width(30.dp).height(height.dp).background(Ot3Theme.colors.accent, RoundedCornerShape(3.dp)))
                Text(if (days.size <= 14) day.optString("day").takeLast(2) else "", style = MaterialTheme.typography.labelSmall, color = Ot3Theme.colors.dim)
            }
        }
    }
    days.getOrNull(selected)?.let { day -> Text("${day.optString("day")} · ${usageNumber(day.optDouble(metric), metric == "cost")}", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun usageNumber(value: Double, money: Boolean): String {
    val locale = LocalConfiguration.current.locales[0]
    return if (money) NumberFormat.getCurrencyInstance(locale).apply { currency = Currency.getInstance("USD") }.format(value)
    else NumberFormat.getIntegerInstance(locale).format(value)
}
