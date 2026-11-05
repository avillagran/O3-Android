package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.transport.NativeParity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal val LocalReaderReference = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** What a file reference opened: text, an image, or why it could not be shown. */
internal data class ReaderFilePreview(
    val path: String,
    val text: String? = null,
    val image: androidx.compose.ui.graphics.ImageBitmap? = null,
    val truncated: Boolean = false,
    val unsupported: Boolean = false,
)

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")
private val MEDIA_EXTENSIONS = setOf("mp4", "mov", "webm", "mkv", "avi", "m4v", "mp3", "wav", "ogg", "oga", "m4a", "flac", "aac", "opus")
private val BINARY_EXTENSIONS = MEDIA_EXTENSIONS + setOf("pdf", "zip", "gz", "tgz", "xz", "zst", "7z", "rar", "jar", "apk", "aar",
    "so", "dylib", "dll", "exe", "bin", "o", "a", "class", "ico", "avif", "bmp", "tif", "tiff", "psd", "woff", "woff2", "ttf", "otf", "sqlite", "db")

internal enum class ReaderFileKind { IMAGE, HTML, BINARY, TEXT }

/** How a referenced path is read: the host only signs HTML and images, text goes through `projects.readFile`. */
internal fun readerFileKind(path: String): ReaderFileKind {
    val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    return when (extension) {
        in IMAGE_EXTENSIONS -> ReaderFileKind.IMAGE
        "html", "htm" -> ReaderFileKind.HTML
        in BINARY_EXTENSIONS -> ReaderFileKind.BINARY
        else -> ReaderFileKind.TEXT
    }
}

/**
 * Opens a file reference the way Desktop's file panel does. T3 asset grants cover only
 * HTML and images (`isWorkspacePreviewEntryPath`), so text files are read with
 * `projects.readFile` from the thread's cwd, which also takes an absolute host path.
 */
internal suspend fun readReaderFile(transport: dev.avillagran.ot3android.core.transport.T3Transport, resource: JSONObject, cwd: String?): ReaderFilePreview {
    val path = resource.getString("path")
    return when (readerFileKind(path)) {
        ReaderFileKind.BINARY -> ReaderFilePreview(path, unsupported = true)
        ReaderFileKind.IMAGE -> {
            val result = transport.document(JSONObject(resource.toString()).put("_tag", "media-file"))
            val data = result.optString("imageData").substringAfter("base64,", "")
            if (data.isEmpty()) ReaderFilePreview(path, unsupported = true)
            else {
                val image = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    decodeReaderImage(android.util.Base64.decode(data, android.util.Base64.DEFAULT), 2048).asImageBitmap()
                }
                ReaderFilePreview(path, image = image)
            }
        }
        ReaderFileKind.HTML -> ReaderFilePreview(path, text = transport.document(resource).getString("contents"))
        ReaderFileKind.TEXT -> {
            val absolute = path.startsWith("/")
            val folder = cwd ?: if (absolute) path.substringBeforeLast('/').ifEmpty { "/" } else throw IllegalStateException("no_workspace")
            val result = transport.call("projects.readFile", JSONObject().put("cwd", folder).put("relativePath", path)) as JSONObject
            val contents = result.optString("contents")
            if (contents.contains('\u0000')) ReaderFilePreview(path, unsupported = true)
            else ReaderFilePreview(path, text = contents, truncated = result.optBoolean("truncated"))
        }
    }
}

/** Authenticated bounded source viewer: HTML is inert selectable source, never a WebView. */
@Composable
internal fun ReaderReferenceHost(connection: Connection?, threadId: String, localEnvironment: String,
    graph: dev.avillagran.ot3android.AppGraph, workspaceCwd: () -> String?, openThread: (String, String) -> Unit, content: @Composable () -> Unit) {
    val worker = rememberCoroutineScope()
    var preview by remember(connection) { mutableStateOf<ReaderFilePreview?>(null) }
    var failed by remember(connection) { mutableStateOf(false) }
    var failure by remember(connection) { mutableStateOf<String?>(null) }
    var busy by remember(connection) { mutableStateOf(false) }
    val current by rememberUpdatedState(connection)
    val cwd by rememberUpdatedState(workspaceCwd)
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val route: (String) -> Unit = { href ->
        val captured = connection
        if (captured != null && !busy) {
            busy = true; failed = false; failure = null
            worker.launch {
                try {
                    val environments = JSONArray(graph.connectionManager.supervisors.value.map { (id, supervisor) ->
                        JSONObject().put("id", id).put("connected", supervisor.connection.value != null)
                            .put("threads", JSONArray(supervisor.shell.value?.threads.orEmpty().map { JSONObject().put("id", it.id) }))
                    })
                    val target = NativeParity().reference(href, localEnvironment, threadId, environments)
                    if (current === captured) when (target.getString("kind")) {
                        "thread" -> openThread(target.getString("environmentId"), target.getString("threadId"))
                        "document" -> {
                            val result = readReaderFile(captured.transport, target.getJSONObject("resource"), cwd())
                            if (current === captured) preview = result
                        }
                        "external" -> uri.openUri(target.getString("url"))
                        else -> failed = true
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    android.util.Log.w("Ot3Reference", "reference failed: ${error.javaClass.simpleName}: ${error.message}")
                    if (current === captured) { failed = true; failure = error.message?.take(160) }
                }
                finally { if (current === captured) busy = false }
            }
        }
    }
    CompositionLocalProvider(LocalReaderReference provides if (connection != null) route else null) { content() }
    val shown = preview
    if (shown != null || failed || busy) AlertDialog(onDismissRequest = { preview = null; failed = false },
        title = { Text(shown?.path?.substringAfterLast('/')?.ifEmpty { null } ?: stringResource(R.string.ot3_inspector_details), maxLines = 2) }, text = {
            if (busy) CircularProgressIndicator()
            else if (failed) Column {
                Text(stringResource(R.string.ot3_inspector_unavailable_error))
                failure?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            else if (shown != null) Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                Text(shown.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                when {
                    shown.image != null -> androidx.compose.foundation.Image(shown.image, contentDescription = shown.path,
                        modifier = Modifier.fillMaxWidth().testTag("reader_reference_image"), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                    shown.unsupported -> Text(stringResource(R.string.ot3_reference_unsupported))
                    else -> {
                        if (shown.truncated) Text(stringResource(R.string.ot3_reference_truncated), style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(shown.text.orEmpty(), Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("reader_reference_text"), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { preview = null; failed = false }) { Text(stringResource(R.string.ot3_dialog_close)) } },
        dismissButton = shown?.let { file -> {
            @Suppress("DEPRECATION") val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(file.path)) }) { Text(stringResource(R.string.ot3_reference_copy_path)) }
        } })
}
