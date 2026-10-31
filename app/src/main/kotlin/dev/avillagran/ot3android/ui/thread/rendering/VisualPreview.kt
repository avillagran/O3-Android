package dev.avillagran.ot3android.ui.thread.rendering

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.thread.*
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class VisualKind { Mermaid, Qml, Html }
internal data class VisualRequest(val kind: VisualKind, val source: String, val title: String)
internal enum class VisualAvailability { Ready, Offline, Expired, Unsupported }

/** A rendering boundary, not a transport implementation. P20 must install a scoped
 * offline local engine or a reader of already-delivered SDK frames. Never pass HTML, SVG or URLs as frames.
 * generation must change with host, thread, authority, theme and renderer settings.
 * render must perform no network I/O, honor cancellation and return a bounded raster (<=10 MiB / 40 MP).
 * No implementation is installed by default; unsupported is explicit. */
internal data class VisualRenderer(
    val generation: Any? = null,
    val owner: String? = null,
    val availability: VisualAvailability = VisualAvailability.Unsupported,
    val isCurrent: () -> Boolean = { false },
    val render: suspend (VisualRequest) -> ByteArray? = { null },
    val open: ((VisualRequest) -> Unit)? = null,
)
internal val LocalVisualRenderer = compositionLocalOf { VisualRenderer() }

internal suspend fun readVisualFrame(renderer: VisualRenderer, request: VisualRequest): ByteArray? {
    if (renderer.availability != VisualAvailability.Ready || renderer.generation == null || !renderer.isCurrent()) return null
    require(request.source.length <= 262_144) { "visual_source_limit" }
    val bytes = withContext(Dispatchers.Default) { withTimeout(10_000) { renderer.render(request) } } ?: return null
    if (!renderer.isCurrent()) return null
    require(bytes.size in 1..10 * 1024 * 1024) { "visual_frame_limit" }
    // The reader validates dimensions and decodes on its bounded worker path.
    val png = bytes.size >= 8 && bytes.take(8) == listOf(137,80,78,71,13,10,26,10).map { it.toByte() }
    val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
    require(png || jpeg) { "visual_frame_format" }
    return bytes
}

/** Shared reader owns viewport gating, retry, bitmap leases, fullscreen zoom and copy.
 * An attempt owns its frame bytes; changing scope destroys the entire surface. */
@Composable
internal fun VisualPreview(request: VisualRequest, id: String) {
    val renderer = LocalVisualRenderer.current
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (!lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
    key(request, renderer.generation, renderer.owner, renderer.availability) {
        Column(Modifier.fillMaxWidth().testTag("visual_preview_$id")) {
            Text(request.title, style = MaterialTheme.typography.labelSmall)
            if (renderer.availability != VisualAvailability.Ready || renderer.generation == null) {
                Text(stringResource(when(renderer.availability) {
                    VisualAvailability.Offline -> R.string.ot3_render_offline
                    VisualAvailability.Expired -> R.string.ot3_render_expired
                    else -> R.string.ot3_render_unsupported
                }))
            } else {
                var dialog by remember { mutableStateOf(false) }
                val source = remember(renderer, request) {
                    ReaderImages(renderer.generation, renderer.isCurrent, renderer.owner) {
                        readVisualFrame(renderer, request)
                    }
                }
                val attachment = remember(request, id) { JSONObject().put("id", id).put("type", "image")
                    .put("source", "visual-frame").put("name", request.title) }
                CompositionLocalProvider(LocalReaderImages provides source, LocalOpenReaderImage provides { dialog = true }) {
                    AttachmentImage(attachment, markdown = true)
                    if (dialog) ReaderImageDialog(attachment) { dialog = false }
                }
                renderer.open?.let { open -> TextButton(onClick = { if (renderer.isCurrent()) open(request) }) {
                    Text(stringResource(R.string.ot3_render_open))
                } }
            }
        }
    }
}

/** Phone adaptation of the side gallery. Selection is scoped by the integration's
 * generation; each preview remains independently cancellable and bounded. */
@Composable
internal fun VisualGallery(requests: List<VisualRequest>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        requests.forEachIndexed { index, request ->
            TextButton(onClick = { onSelect(index) }) { Text(request.title) }
        }
        requests.getOrNull(selected)?.let { VisualPreview(it, "gallery_$selected") }
    }
}
