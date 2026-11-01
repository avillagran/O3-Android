package dev.avillagran.ot3android.ui.settings.storage

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.appGraph
import kotlinx.coroutines.launch

/** Phone storage remains usable even without a selected or connected host. */
@Composable
fun LocalCacheSettings() {
    val context = LocalContext.current
    val cache = context.appGraph.threadCache
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<dev.avillagran.ot3android.core.thread.ThreadCache.Usage?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(cache) { usage = cache.usage() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(R.string.cache_title))
        }
        if (expanded) dev.avillagran.ot3android.ui.kit.Ot3Sheet(
            onDismissRequest = { expanded = false }, title = stringResource(R.string.cache_title)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
        usage?.let { current ->
            Text(stringResource(R.string.cache_usage, current.bytes / (1024 * 1024), current.limit / (1024 * 1024)))
            Row {
                listOf(50L, 200L, 500L).forEach { mb ->
                    FilterChip(selected = current.limit == mb * 1024 * 1024, enabled = !busy,
                        onClick = { busy = true; scope.launch {
                            try {
                                val bytes = mb * 1024 * 1024
                                context.getSharedPreferences("thread-cache", Context.MODE_PRIVATE).edit().putLong("limit", bytes).apply()
                                cache.configure(bytes); usage = cache.usage()
                            } finally { busy = false }
                        } }, label = { Text("$mb MB") })
                }
            }
        }
        TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
            try { cache.clear(); usage = cache.usage() } finally { busy = false }
        } }) { Text(stringResource(R.string.cache_clear)) }
        }
        }
    }
}
