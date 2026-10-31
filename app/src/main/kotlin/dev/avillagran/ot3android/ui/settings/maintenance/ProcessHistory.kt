package dev.avillagran.ot3android.ui.settings.maintenance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONArray

@Composable
internal fun ProcessHistory(buckets: JSONArray?) {
    val points = buckets.objects().take(60)
    if (points.size < 2) return
    val cpu = points.map { it.optDouble("avgCpuPercent", 0.0).takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0 }
    val rss = points.map { it.optDouble("maxRssBytes", 0.0).takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0 }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Text(maintenanceText("Last 30 minutes: CPU (line) and memory (bars)"))
    val description = "CPU ${cpu.last()} · RSS ${rss.last()}"
    Canvas(Modifier.fillMaxWidth().height(100.dp).semantics { contentDescription = description }) {
        val step = size.width / points.size
        val maxRss = maxOf(1.0, rss.max())
        val maxCpu = maxOf(10.0, cpu.max())
        points.indices.forEach { i ->
            val height = (rss[i] / maxRss * size.height).toFloat()
            drawRect(secondary.copy(alpha = 0.3f), Offset(i * step + 1, size.height - height), androidx.compose.ui.geometry.Size((step - 2).coerceAtLeast(1f), height))
            if (i > 0) drawLine(primary, Offset((i - 0.5f) * step, (size.height * (1 - cpu[i - 1] / maxCpu)).toFloat()),
                Offset((i + 0.5f) * step, (size.height * (1 - cpu[i] / maxCpu)).toFloat()), strokeWidth = 2.dp.toPx())
        }
    }
}
