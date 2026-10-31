package dev.avillagran.ot3android.ui.thread

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.ot3android.ui.navigation.LocalSelectedThreadKey
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import dev.avillagran.ot3android.ui.kit.CopyButton
import dev.avillagran.ot3android.ui.kit.CopyState
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.avillagran.ot3android.ui.kit.Ot3Spinner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

internal data class ReaderImages(val generation: Any?, val isCurrent: () -> Boolean = { true }, val owner: String? = null, val read: suspend (JSONObject) -> ByteArray?)
private val imageReads = Semaphore(2)
internal val LocalReaderImages = compositionLocalOf { ReaderImages(null) { null } }

/** Attachments and Markdown share the bounded reader, viewer and clipboard. */
@Composable
internal fun MessageAttachments(row: JSONObject) {
    row.optJSONArray("attachments").objects().forEach { attachment ->
        if (attachment.optString("type") == "image") AttachmentImage(attachment)
        else if (dev.avillagran.ot3android.core.thread.MessageMedia.mime(attachment.optString("name"), attachment.optString("mimeType")) != null) {
            val open = LocalOpenReaderImage.current
            TextButton(onClick = { open(attachment) }) { Text(attachment.optString("name")) }
        } else Text(attachment.optString("name"), style = MaterialTheme.typography.bodySmall)
    }
}

/** Bounds are read before decoding. Neither compressed bytes nor full-size pixels are cached. */
internal fun decodeReaderImage(bytes: ByteArray, maxEdge: Int, onNaturalWidth: (Int) -> Unit = {}): Bitmap {
    val bounds = readerImageBounds(bytes, 40_000_000)
    onNaturalWidth(bounds.outWidth)
    val sample = readerImageSample(bounds.outWidth, bounds.outHeight, maxEdge)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "image_decode_failed" }
}

private fun readerImageBounds(bytes: ByteArray, pixelLimit: Long): BitmapFactory.Options {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "image_decode_failed" }
    require(bounds.outWidth.toLong() * bounds.outHeight <= pixelLimit) { "invalid_image" }
    return bounds
}

internal fun readerImageSample(width: Int, height: Int, maxEdge: Int): Int {
    require(width > 0 && height > 0 && maxEdge in 64..2048)
    var sample = 1
    while ((width.toLong() + sample - 1) / sample > maxEdge ||
        (height.toLong() + sample - 1) / sample > maxEdge) sample *= 2
    return sample
}

internal val LocalOpenReaderImage = compositionLocalOf<(JSONObject) -> Unit> { {} }

@Composable
internal fun AttachmentImage(attachment: JSONObject, markdown: Boolean = false) {
    val source = LocalReaderImages.current
    val open = LocalOpenReaderImage.current
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (!lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
    key(attachment.toString(), source.generation) {
        var naturalWidth by remember { mutableIntStateOf(0) }
        Box(if (markdown) Modifier.widthIn(max = if (naturalWidth > 0) naturalWidth.dp else androidx.compose.ui.unit.Dp.Infinity).fillMaxWidth() else Modifier.size(220.dp, 160.dp)) {
            ImageRead(attachment, attachment.toString(), source, 640,
                (if (markdown) Modifier.fillMaxWidth() else Modifier.size(220.dp, 160.dp)).clickable(onClickLabel = attachment.optString("name")) { open(attachment) }, onNaturalWidth = { naturalWidth = it })
            Box(Modifier.align(Alignment.TopEnd)) {
                ImageCopyButton(attachment, source, "thumbnail", Modifier
                    .background(Ot3Theme.colors.surface.copy(alpha = 0.95f), RoundedCornerShape(6.dp)))
            }
        }
    }
}

@Composable
internal fun ReaderImageDialog(attachment: JSONObject, close: () -> Unit) {
    if (!attachment.has("source") && dev.avillagran.ot3android.core.thread.MessageMedia.mime(attachment.optString("name"), attachment.optString("mimeType")) != null) {
        ReaderMediaDialog(attachment, close)
        return
    }
    val source = LocalReaderImages.current
    val owner = source.owner ?: LocalSelectedThreadKey.current
    val savedIdentity = org.json.JSONArray().put(attachment.toString()).put(owner)
        .put(source.generation.takeIf { it is String || it is Number }).toString()
    val zoom = rememberSaveable(attachment.toString(), source.generation, owner, saver = ReaderImageZoom.scopedSaver(savedIdentity)) { ReaderImageZoom() }
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (!lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
    key(attachment.toString(), source.generation, owner) {
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(attachment.optString("name"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ImageCopyButton(attachment, source, "viewer")
                        TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp).testTag("thread_image_close")) {
                            Text(stringResource(R.string.ot3_dialog_close))
                        }
                    }
                    ImageRead(attachment, attachment.toString(), source, 2048, Modifier.weight(1f).fillMaxWidth(), zoom)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Ot3IconButton("zoom-out", stringResource(R.string.ot3_image_zoom_out), { zoom.transform(zoom.center(), Offset.Zero, 0.5f) }, enabled = zoom.scale > 1f, modifier = Modifier.testTag("thread_image_zoom_out"))
                        Ot3IconButton("rotate-ccw", stringResource(R.string.ot3_image_zoom_reset), zoom::reset, enabled = zoom.scale > 1f, modifier = Modifier.testTag("thread_image_zoom_reset"))
                        Ot3IconButton("zoom-in", stringResource(R.string.ot3_image_zoom_in), { zoom.transform(zoom.center(), Offset.Zero, 2f) }, enabled = zoom.fitted.width > 0 && zoom.scale < ReaderImageZoom.MAX_SCALE, modifier = Modifier.testTag("thread_image_zoom_in"))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageRead(attachment: JSONObject, identity: String, source: ReaderImages, maxEdge: Int, modifier: Modifier, zoom: ReaderImageZoom? = null, onNaturalWidth: (Int) -> Unit = {}) {
    var retry by remember(identity, source.generation) { mutableIntStateOf(0) }
    var complete by remember(identity, source.generation, retry) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, identity, source.generation, retry) {
        try {
            var naturalWidth = 0
            val decoded = if (source.generation == null) null else imageReads.withPermit {
                val bytes = source.read(attachment)
                if (bytes == null) null else withContext(Dispatchers.Default) { decodeReaderImage(bytes, maxEdge) { naturalWidth = it } }
            }
            // Native callbacks may resume on a worker. Publish Compose/Bitmap state on Main.
            withContext(Dispatchers.Main.immediate) {
                if (source.isCurrent()) { onNaturalWidth(naturalWidth); value = decoded }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Native validation/decode failure: an explicit retry starts a new read. */ }
        finally { withContext(Dispatchers.Main.immediate) { complete = true } }
    }
    Surface(shape = RoundedCornerShape(8.dp), modifier = modifier.testTag("thread_image_${attachment.optString("id")}_$maxEdge"), color = MaterialTheme.colorScheme.surfaceVariant) {
        val decoded = bitmap
        if (decoded != null) {
            if (zoom == null) Image(decoded.asImageBitmap(), attachment.optString("name"), contentScale = ContentScale.Fit, modifier = if (attachment.has("source")) Modifier.fillMaxWidth().aspectRatio(decoded.width.toFloat() / decoded.height) else Modifier.fillMaxSize())
            else ZoomedReaderImage(decoded, attachment.optString("name"), zoom)
        }
        else Column(Modifier.padding(12.dp)) {
            Text(attachment.optString("name"), style = MaterialTheme.typography.bodySmall)
            if (!complete) Ot3Spinner(size = 16.dp)
            else {
                Text(stringResource(R.string.ot3_usage_unavailable), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.ot3_dialog_retry)) }
            }
        }
    }
}

/** Always visible on touch surfaces; a lifecycle/generation change disposes and cancels this action. */
@Composable
private fun ImageCopyButton(attachment: JSONObject, source: ReaderImages, location: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(source)
    var state by remember { mutableStateOf(CopyState.IDLE) }
    val label = stringResource(R.string.ot3_copy_image)
    LaunchedEffect(state) {
        if (state.resets) { delay(CopyState.RESET_MS); state = CopyState.IDLE }
    }
    CopyButton(state = state, enabled = source.generation != null, label = label,
        modifier = modifier.testTag("thread_image_copy_$location"),
        onClick = {
            state = CopyState.BUSY
            scope.launch {
                try {
                    imageReads.withPermit {
                        val bytes = requireNotNull(source.read(attachment))
                        withContext(Dispatchers.Default) { readerImageBounds(bytes, 16_000_000) }
                        ImageClipboard.copy(context, bytes, attachment.optString("mimeType").ifEmpty { dev.avillagran.ot3android.core.thread.PublicMarkdownImage.mime(bytes) }, label) {
                            latest.generation == source.generation && source.isCurrent()
                        }
                    }
                    state = CopyState.COPIED
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { state = CopyState.ERROR }
            }
        })
}

private fun ReaderImageZoom.center() = Offset(viewport.width / 2, viewport.height / 2)

@Composable
private fun ZoomedReaderImage(bitmap: Bitmap, name: String, zoom: ReaderImageZoom) {
    val zoomIn = stringResource(R.string.ot3_image_zoom_in)
    val zoomOut = stringResource(R.string.ot3_image_zoom_out)
    val reset = stringResource(R.string.ot3_image_zoom_reset)
    Box(Modifier.fillMaxSize().clipToBounds().testTag("thread_image_zoom_viewport")
        .onSizeChanged { zoom.resize(Size(it.width.toFloat(), it.height.toFloat()), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) }
        .semantics {
            stateDescription = "${(zoom.scale * 100).toInt()}%"
            horizontalScrollAxisRange = ScrollAxisRange({ zoom.panRange.x - zoom.translation.x }, { 2 * zoom.panRange.x })
            verticalScrollAxisRange = ScrollAxisRange({ zoom.panRange.y - zoom.translation.y }, { 2 * zoom.panRange.y })
            if (zoom.scale > 1f) scrollBy { x, y -> zoom.transform(zoom.center(), Offset(-x, -y), 1f); true }
            customActions = buildList {
                if (zoom.scale < ReaderImageZoom.MAX_SCALE) add(CustomAccessibilityAction(zoomIn) { zoom.transform(zoom.center(), Offset.Zero, 2f); true })
                if (zoom.scale > 1f) {
                    add(CustomAccessibilityAction(zoomOut) { zoom.transform(zoom.center(), Offset.Zero, 0.5f); true })
                    add(CustomAccessibilityAction(reset) { zoom.reset(); true })
                }
            }
        }
        .pointerInput(zoom) { detectTapGestures(onDoubleTap = zoom::doubleTap) }
        .pointerInput(zoom) { detectTransformGestures { centroid, pan, factor, _ -> zoom.transform(centroid, pan, factor) } }
    ) {
        Image(bitmap.asImageBitmap(), name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.scale; scaleY = zoom.scale
                translationX = zoom.translation.x; translationY = zoom.translation.y
            })
    }
}

/** Media uses the same authenticated reader and dialog owner; only the decoder is platform media. */
@Composable
private fun ReaderMediaDialog(attachment: JSONObject, close: () -> Unit) {
    val source = LocalReaderImages.current
    val context = LocalContext.current
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (!lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
    var retry by remember(attachment.toString(), source.generation) { mutableIntStateOf(0) }
    var failed by remember(attachment.toString(), source.generation, retry) { mutableStateOf(false) }
    var prepared by remember(attachment.toString(), source.generation, retry) { mutableStateOf(false) }
    val file by produceState<java.io.File?>(null, attachment.toString(), source.generation, retry) {
        var created: java.io.File? = null
        try {
            val bytes = imageReads.withPermit { requireNotNull(source.read(attachment)) }
            require(bytes.isNotEmpty() && bytes.size <= 50 * 1024 * 1024 && source.isCurrent())
            withContext(Dispatchers.IO) {
                val directory = java.io.File(context.cacheDir, "reader-media").apply { check(mkdirs() || isDirectory) }
                created = java.io.File.createTempFile("media-", ".bin", directory)
                created!!.outputStream().use { it.write(bytes) }
            }
            if (source.isCurrent()) value = created
            kotlinx.coroutines.awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { created?.delete() }
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(attachment.optString("name"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = close) { Text(stringResource(R.string.ot3_dialog_close)) }
                }
                if (failed) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.ot3_dialog_retry)) }
                else {
                    if (!prepared) Ot3Spinner(size = 16.dp)
                    file?.let { local ->
                        key(local.path) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                factory = { viewContext ->
                                    android.widget.VideoView(viewContext).apply {
                                        setMediaController(android.widget.MediaController(viewContext).also { it.setAnchorView(this) })
                                        setOnPreparedListener { prepared = true; start() }
                                        setOnErrorListener { _, _, _ -> failed = true; true }
                                        setVideoURI(android.net.Uri.fromFile(local))
                                    }
                                },
                                onRelease = { it.stopPlayback() },
                            )
                        }
                    }
                }
            }
        }
    }
}
