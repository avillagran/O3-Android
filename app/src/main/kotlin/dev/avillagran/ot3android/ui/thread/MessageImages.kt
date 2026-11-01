package dev.avillagran.ot3android.ui.thread

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.ot3android.ui.navigation.LocalSelectedThreadKey
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import dev.avillagran.ot3android.ui.kit.CopyButton
import dev.avillagran.ot3android.ui.kit.CopyState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.Ot3Theme
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

/** Validate source bounds before allocating sampled pixels. */
internal fun decodeReaderImage(bytes: ByteArray, maxEdge: Int, inline: Boolean = false, onNaturalWidth: (Int) -> Unit = {}): Bitmap {
    val bounds = readerImageBounds(bytes, 40_000_000)
    onNaturalWidth(bounds.outWidth)
    if (inline && android.os.Build.VERSION.SDK_INT >= 28) {
        val size = readerInlineImageSize(bounds.outWidth, bounds.outHeight, maxEdge)
        return android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            decoder.setTargetSize(size.first, size.second)
        }
    }
    val sample = if (inline) readerInlineImageSample(bounds.outWidth, bounds.outHeight, maxEdge) else readerImageSample(bounds.outWidth, bounds.outHeight, maxEdge)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        if (android.os.Build.VERSION.SDK_INT >= 26) inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
    }
    val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "image_decode_failed" }
    if (!inline) return decoded
    val size = readerInlineImageSize(bounds.outWidth, bounds.outHeight, maxEdge)
    return try {
        Bitmap.createScaledBitmap(decoded, size.first, size.second, true)
            .also { if (it !== decoded) decoded.recycle() }
    } catch (error: Throwable) { decoded.recycle(); throw error }
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

/** Preserve every displayed pixel, except at the explicit 4 MP inline safety ceiling. */
internal fun readerInlineImageSample(width: Int, height: Int, displayWidthPx: Int): Int {
    require(width > 0 && height > 0 && displayWidthPx > 0)
    var sample = 1
    while (width.toLong() / (sample.toLong() * 2) >= displayWidthPx) sample *= 2
    while (readerImageAllocation(width, height, sample) > 16L * 1024 * 1024) sample *= 2
    return sample
}

/** Decode at physical display pixels, never upscale, bounded to 4 MP for extreme aspect ratios. */
internal fun readerInlineImageSize(width: Int, height: Int, displayWidthPx: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && displayWidthPx > 0)
    val scale = minOf(1.0, displayWidthPx.toDouble() / width,
        kotlin.math.sqrt((16.0 * 1024 * 1024 / 4) / (width.toDouble() * height)))
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

internal fun readerImageAllocation(width: Int, height: Int, sample: Int): Long =
    ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) * 4

internal val LocalOpenReaderImage = compositionLocalOf<(JSONObject) -> Unit> { {} }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentImage(attachment: JSONObject, markdown: Boolean = false) {
    val source = LocalReaderImages.current
    val open = LocalOpenReaderImage.current
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (!lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
    key(attachment.toString(), source.generation) {
        var naturalWidth by remember { mutableIntStateOf(0) }
        var showMenu by remember { mutableStateOf(false) }
        val copyLabel = stringResource(R.string.ot3_copy_image)
        Box(if (markdown) Modifier.widthIn(max = if (naturalWidth > 0) naturalWidth.dp else androidx.compose.ui.unit.Dp.Infinity).fillMaxWidth() else Modifier.size(220.dp, 160.dp)
            // Desktop frames attachments; a Markdown image is bare and square.
            .then(if (markdown) Modifier else Modifier.clip(RoundedCornerShape(8.dp)).background(Ot3Theme.colors.foreground.copy(alpha = .04f))
                .border(1.dp, Ot3Theme.colors.foreground.copy(alpha = .12f), RoundedCornerShape(8.dp)))) {
            ImageRead(attachment, attachment.toString(), source, 640, square = markdown, modifier =
                (if (markdown) Modifier.fillMaxWidth() else Modifier.fillMaxSize().padding(4.dp)).combinedClickable(onClickLabel = attachment.optString("name"),
                    onClick = { open(attachment) }, onLongClickLabel = copyLabel,
                    onLongClick = { showMenu = true })
                    .semantics { customActions = listOf(CustomAccessibilityAction(copyLabel) { showMenu = true; true }) }, onNaturalWidth = { naturalWidth = it }, fitThumbnail = !markdown)
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                ImageCopyButton(attachment, source, "menu")
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

/** Keep lightweight layout placeholders for eagerly composed Markdown blocks, but
 * load/retain pixels only in the clipped viewport (including nested cells). */
internal fun readerImageNearViewport(top: Float, bottom: Float, left: Float, right: Float, width: Int, height: Int): Boolean =
    width > 0 && height > 0 && bottom > 0 && top < height && right > 0 && left < width

@Composable
private fun ImageRead(attachment: JSONObject, identity: String, source: ReaderImages, maxEdge: Int, modifier: Modifier, zoom: ReaderImageZoom? = null, onNaturalWidth: (Int) -> Unit = {}, fitThumbnail: Boolean = false, square: Boolean = false) {
    var displayWidth by remember(identity, source.generation) { mutableIntStateOf(0) }
    var near by remember(identity, source.generation) { mutableStateOf(false) }
    var aspect by remember(identity, source.generation) { mutableFloatStateOf(1f) }
    Box(modifier.testTag("thread_image_${attachment.optString("id")}_$maxEdge").onSizeChanged { displayWidth = it.width }.onGloballyPositioned { coordinates ->
        // boundsInWindow clips against ancestor scroll containers. Offscreen composed
        // Markdown blocks must not retain leases or occupy the budget wait queue.
        val visible = coordinates.boundsInWindow()
        near = visible.width > 0f && visible.height > 0f
    }) {
        if (near && displayWidth > 0) key(identity, source.generation) {
            ActiveImageRead(attachment, identity, source, square, if (zoom == null) displayWidth else maxEdge,
                Modifier.fillMaxWidth().then(if (fitThumbnail) Modifier.fillMaxHeight() else Modifier), zoom, onNaturalWidth, (displayWidth / aspect).toInt(), fitThumbnail, onDimensions = { w, h -> aspect = w.toFloat() / h })
        } else Spacer(Modifier.fillMaxWidth().aspectRatio(aspect))
    }
}

@Composable
private fun ActiveImageRead(attachment: JSONObject, identity: String, source: ReaderImages, square: Boolean, maxEdge: Int, modifier: Modifier, zoom: ReaderImageZoom? = null, onNaturalWidth: (Int) -> Unit = {}, loadingHeightPx: Int = 160, fitThumbnail: Boolean = false, onDimensions: (Int, Int) -> Unit = { _, _ -> }) {
    val cachedBytes = remember(identity, source.generation) { arrayOfNulls<ByteArray>(1) }
    var retry by remember(identity, source.generation) { mutableIntStateOf(0) }
    var complete by remember(identity, source.generation, retry) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, identity, source.generation, retry, maxEdge) {
        value = null
        complete = false
        var lease: ReaderBitmapBudget.Lease? = null
        var thumbnailLease: ReaderBitmapBudget.Lease? = null
        try {
            var naturalWidth = 0
            val decoded = if (source.generation == null) null else {
                // Gate pending reads too: eager Markdown placeholders can otherwise
                // read many compressed images while waiting for their pixel budget.
                if (zoom == null) thumbnailLease = ReaderBitmapBudget.thumbnails.acquire(640 * 640 * 4)
                lease = ReaderBitmapBudget.shared.acquire(640 * 640 * 4)
                val bytes = cachedBytes[0] ?: imageReads.withPermit { source.read(attachment) }?.also { cachedBytes[0] = it }
                if (bytes == null) null else {
                    val bounds = withContext(Dispatchers.Default) { readerImageBounds(bytes, 40_000_000) }
                    onDimensions(bounds.outWidth, bounds.outHeight)
                    onNaturalWidth(bounds.outWidth)
                    val sample = if (zoom == null) readerInlineImageSample(bounds.outWidth, bounds.outHeight, maxEdge)
                        else readerImageSample(bounds.outWidth, bounds.outHeight, maxEdge)
                    val size = readerInlineImageSize(bounds.outWidth, bounds.outHeight, maxEdge)
                    val reservation = if (zoom == null) size.first * size.second * 4
                        else readerImageAllocation(bounds.outWidth, bounds.outHeight, sample).toInt()
                    lease.close(); lease = null
                    thumbnailLease?.close(); thumbnailLease = null
                    if (zoom == null) thumbnailLease = ReaderBitmapBudget.thumbnails.acquire(reservation)
                    lease = ReaderBitmapBudget.shared.acquire(reservation)
                    imageReads.withPermit {
                        // API 24–27 needs a sampled intermediate before exact scaling.
                        // Its independent scratch budget cannot block behind displayed leases.
                        val scratch = if (zoom == null && android.os.Build.VERSION.SDK_INT < 28)
                            ReaderBitmapBudget.scratch.acquire(readerImageAllocation(bounds.outWidth, bounds.outHeight, sample).toInt()) else null
                        try {
                            withContext(Dispatchers.Default) { decodeReaderImage(bytes, maxEdge, inline = zoom == null) { naturalWidth = it } }
                        } finally { scratch?.close() }
                    }
                }
            }
            if (source.isCurrent()) { onNaturalWidth(naturalWidth); value = decoded }
            complete = true
            if (value != null) kotlinx.coroutines.awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { complete = true }
        finally { lease?.close(); thumbnailLease?.close(); value = null }
    }
    val loadingHeight = with(LocalDensity.current) { loadingHeightPx.toDp() }
    val stableModifier = if (bitmap != null) modifier else if (square) modifier.height(loadingHeight) else modifier.heightIn(min = loadingHeight)
    Surface(shape = if (square) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(8.dp), modifier = stableModifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        val decoded = bitmap
        if (decoded != null) {
            if (zoom == null) Image(decoded.asImageBitmap(), attachment.optString("name"), contentScale = ContentScale.Fit, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = if (fitThumbnail) Modifier.fillMaxSize() else if (attachment.has("source")) Modifier.fillMaxWidth().aspectRatio(decoded.width.toFloat() / decoded.height) else Modifier.fillMaxSize())
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

/** Explicit menu/viewer copy; a lifecycle/generation change disposes and cancels this action. */
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
    val copy: () -> Unit = {
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
    }
    if (location == "menu") {
        val status = when (state) {
            CopyState.COPIED -> stringResource(R.string.ot3_kit_copied)
            CopyState.ERROR -> stringResource(R.string.ot3_kit_copy_failed)
            else -> label
        }
        DropdownMenuItem(
            text = { Text(status) },
            leadingIcon = {
                if (state == CopyState.BUSY) Ot3Spinner(size = 20.dp)
                else dev.avillagran.ot3android.ui.kit.Ot3Icon(state.iconName, null)
            },
            enabled = source.generation != null && state != CopyState.BUSY,
            onClick = copy,
            modifier = modifier.testTag("thread_image_copy_$location").semantics { contentDescription = status }
        )
    } else CopyButton(state = state, enabled = source.generation != null, label = label,
        modifier = modifier.testTag("thread_image_copy_$location"), onClick = copy)
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
        Image(bitmap.asImageBitmap(), name, contentScale = ContentScale.Fit, filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
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
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        key(attachment.toString(), source.generation, retry) {
            var active by remember { mutableStateOf(true) }
            DisposableEffect(Unit) { onDispose { active = false } }
            var failed by remember { mutableStateOf(false) }
            var prepared by remember { mutableStateOf(false) }
            val file by produceState<java.io.File?>(null) {
                var created: java.io.File? = null
                try {
                    val bytes = imageReads.withPermit { requireNotNull(source.read(attachment)) }
                    require(bytes.isNotEmpty() && bytes.size <= 50 * 1024 * 1024 && source.isCurrent())
                    withContext(Dispatchers.IO) {
                        created = ReaderMediaFiles.create(java.io.File(context.cacheDir, "reader-media"), bytes)
                    }
                    if (source.isCurrent()) value = created
                    kotlinx.coroutines.awaitCancellation()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { created?.let(ReaderMediaFiles::release) }
                }
            }
            // Release the failed/forgotten attempt before Retry can allocate another 50 MiB.
            val attemptFile = file
            DisposableEffect(attemptFile, failed) {
                if (failed) attemptFile?.let(ReaderMediaFiles::release)
                onDispose { attemptFile?.let(ReaderMediaFiles::release) }
            }
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
                                            setOnPreparedListener { if (active && source.isCurrent()) { prepared = true; start() } }
                                            setOnErrorListener { _, _, _ -> if (active && source.isCurrent()) failed = true; true }
                                            setVideoURI(android.net.Uri.fromFile(local))
                                        }
                                    },
                                    onRelease = { it.setOnPreparedListener(null); it.setOnErrorListener(null); it.stopPlayback() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
