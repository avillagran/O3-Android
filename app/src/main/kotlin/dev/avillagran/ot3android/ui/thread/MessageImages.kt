package dev.avillagran.ot3android.ui.thread

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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

internal data class ReaderImages(val generation: Any?, val isCurrent: () -> Boolean = { true }, val read: suspend (JSONObject) -> ByteArray?)
private val imageReads = Semaphore(2)
internal val LocalReaderImages = compositionLocalOf { ReaderImages(null) { null } }

/** Only native attachment descriptors enter the binary image API; sources/URLs never do. */
@Composable
internal fun MessageAttachments(row: JSONObject) {
    row.optJSONArray("attachments").objects().forEach { attachment ->
        if (attachment.optString("type") == "image") AttachmentImage(attachment)
        else Text(attachment.optString("name"), style = MaterialTheme.typography.bodySmall)
    }
}

/** Bounds are read before decoding. Neither compressed bytes nor full-size pixels are cached. */
internal fun decodeReaderImage(bytes: ByteArray, maxEdge: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "image_decode_failed" }
    val sample = readerImageSample(bounds.outWidth, bounds.outHeight, maxEdge)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "image_decode_failed" }
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
private fun AttachmentImage(attachment: JSONObject) {
    val source = LocalReaderImages.current
    val open = LocalOpenReaderImage.current
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (!lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
    key(attachment.toString(), source.generation) {
        Box(Modifier.size(220.dp, 160.dp)) {
            ImageRead(attachment, attachment.toString(), source, 640,
                Modifier.size(220.dp, 160.dp).clickable(onClickLabel = attachment.optString("name")) { open(attachment) })
            Box(Modifier.align(Alignment.TopEnd)) {
                ImageCopyButton(attachment, source, "thumbnail", Modifier
                    .background(Ot3Theme.colors.surface.copy(alpha = 0.95f), RoundedCornerShape(6.dp)))
            }
        }
    }
}

@Composable
internal fun ReaderImageDialog(attachment: JSONObject, close: () -> Unit) {
    val source = LocalReaderImages.current
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (!lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
    key(attachment.toString(), source.generation) {
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                    Row {
                        Text(attachment.optString("name"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ImageCopyButton(attachment, source, "viewer")
                        TextButton(onClick = close, modifier = Modifier.testTag("thread_image_close")) {
                            Text(stringResource(R.string.ot3_dialog_close))
                        }
                    }
                    ImageRead(attachment, attachment.toString(), source, 2048, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun ImageRead(attachment: JSONObject, identity: String, source: ReaderImages, maxEdge: Int, modifier: Modifier) {
    var retry by remember(identity, source.generation) { mutableIntStateOf(0) }
    var complete by remember(identity, source.generation, retry) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, identity, source.generation, retry) {
        try {
            value = if (source.generation == null) null else imageReads.withPermit {
                val bytes = source.read(attachment)
                if (bytes == null) null else withContext(Dispatchers.Default) { decodeReaderImage(bytes, maxEdge) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Native validation/decode failure: an explicit retry starts a new read. */ }
        finally { complete = true }
    }
    Surface(shape = RoundedCornerShape(8.dp), modifier = modifier.testTag("thread_image_${attachment.optString("id")}_$maxEdge"), color = MaterialTheme.colorScheme.surfaceVariant) {
        val decoded = bitmap
        if (decoded != null) Image(decoded.asImageBitmap(), attachment.optString("name"), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Column(Modifier.padding(12.dp)) {
            Text(attachment.optString("name"), style = MaterialTheme.typography.bodySmall)
            if (!complete) Ot3Spinner(size = 16.dp)
            else TextButton(onClick = { retry++ }) { Text(stringResource(R.string.ot3_dialog_retry)) }
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
                        ImageClipboard.copy(context, bytes, attachment.getString("mimeType"), label) {
                            latest.generation == source.generation && source.isCurrent()
                        }
                    }
                    state = CopyState.COPIED
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { state = CopyState.ERROR }
            }
        })
}
