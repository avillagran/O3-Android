package dev.avillagran.ot3android.ui.settings.about

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.sky.SkyBrandRow
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** APK-local notices: independent of host connectivity and permissions. */
data class LicenseEntry(val name: String, val version: String, val license: String, val file: String, val source: String)

object BundledLicenses {
    fun load(context: Context): List<LicenseEntry> {
        val json = JSONArray(context.assets.open("licenses/index.json").bufferedReader().use { it.readText() })
        return List(json.length()) { i -> json.getJSONObject(i).let {
            LicenseEntry(it.getString("name"), it.getString("version"), it.getString("license"), it.getString("file"), it.getString("source"))
        } }
    }
    fun filter(entries: List<LicenseEntry>, query: String): List<LicenseEntry> {
        val terms = query.trim().lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return entries.filter { entry -> terms.all { term ->
            "${entry.name} ${entry.version} ${entry.license}".lowercase(java.util.Locale.ROOT).contains(term)
        } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, initialLicenses: Boolean = false) {
    var licenses by rememberSaveable { mutableStateOf(initialLicenses) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var entries by remember { mutableStateOf<List<LicenseEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        val result = withContext(Dispatchers.IO) { runCatching { BundledLicenses.load(context) } }
        entries = result.getOrNull()
        failed = result.isFailure
    }
    androidx.activity.compose.BackHandler(enabled = licenses && !initialLicenses) { licenses = false }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(if (licenses) R.string.about_licenses else R.string.about_title)) },
            navigationIcon = { IconButton(modifier = Modifier.size(48.dp), onClick = { if (licenses && !initialLicenses) licenses = false else onBack() }) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
            } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!licenses) {
                item { SkyBrandRow(Ot3Theme.colors.foreground, Ot3Theme.colors.dim, elapsedMs = 0L) }
                item {
                    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
                    Text(stringResource(R.string.about_version, androidx.core.text.BidiFormatter.getInstance().unicodeWrap(version)), Modifier.semantics { heading() })
                }
                item { Text(stringResource(R.string.about_description)) }
                item { Text(stringResource(R.string.about_translations)) }
                item { TextButton(onClick = { uri.openUri("https://github.com/avillagran/O3-Android") }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.about_source))
                } }
                item { Button(onClick = { licenses = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.about_licenses)) } }
            } else {
                item { OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.about_search)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true) }
                if (failed) item {
                    Text(stringResource(R.string.about_unavailable))
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.about_retry)) }
                } else if (entries == null) item { Text(stringResource(R.string.about_loading)) }
                else {
                    val filtered = BundledLicenses.filter(entries.orEmpty(), query)
                    item { Text(stringResource(R.string.about_count, filtered.size, entries.orEmpty().size),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (filtered.isEmpty()) item { Text(stringResource(R.string.about_empty)) }
                    items(filtered, key = { it.file }) { entry ->
                        val open = expanded == entry.file
                        val state = stringResource(if (open) R.string.about_expanded else R.string.about_collapsed)
                        Column {
                            ListItem(headlineContent = { Text(entry.name, style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.ContentOrLtr)) }, supportingContent = { Text(listOf(entry.version, entry.license).filter { it.isNotBlank() }.joinToString(" · "), style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { stateDescription = state }
                                    .clickable(role = Role.Button) { expanded = if (open) null else entry.file })
                            if (open) {
                                var notice by remember(entry.file) { mutableStateOf<String?>(null) }
                                var error by remember(entry.file) { mutableStateOf(false) }
                                LaunchedEffect(entry.file, attempt) {
                                    val result = withContext(Dispatchers.IO) { runCatching {
                                        context.assets.open("licenses/${entry.file}").bufferedReader().use { it.readText() }
                                    } }
                                    notice = result.getOrNull(); error = result.isFailure
                                }
                                TextButton(onClick = { uri.openUri(entry.source) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.about_project_source, androidx.core.text.BidiFormatter.getInstance().unicodeWrap(entry.name)))
                                }
                                SelectionContainer { Text(notice ?: stringResource(if (error) R.string.about_unavailable else R.string.about_loading)) }
                                if (error) TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.about_retry)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
