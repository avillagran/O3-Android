package dev.avillagran.ot3android.ui.pair

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.pairing.PairingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object QrScanScreenTags {
    const val ROOT = "qr_scan_screen"
    const val PREVIEW = "qr_scan_preview"
    const val PERMISSION = "qr_scan_permission"
    const val REJECTED = "qr_scan_rejected"
}

/** How long "Not a T3 pairing code" stays up after the last such code was seen. */
private const val REJECT_SHOWN_MS = 2_000L

/** How often, at most, a code that is not a pairing link is reported while it stays in view. */
private const val REJECT_REPEAT_MS = 500L

/**
 * Camera preview that reads QR codes until one is a T3 pairing link ([accept]) and hands its
 * text to [onScanned] (on the main thread); any other code is answered with "Not a T3 pairing
 * code" and scanning goes on. The camera permission is asked for here, when the user opens the
 * scanner; nothing is recorded or stored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    accept: (String) -> Boolean = PairingService::isPairingLink,
) {
    SecureWindow()
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }

    Scaffold(
        modifier = modifier.testTag(QrScanScreenTags.ROOT),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (granted) {
                // Bumped (at most every REJECT_REPEAT_MS) while the camera sees a code that is
                // not a pairing link; the message stays up for REJECT_SHOWN_MS after the last.
                var rejections by remember { mutableIntStateOf(0) }
                var rejected by remember { mutableStateOf(false) }
                LaunchedEffect(rejections) {
                    if (rejections == 0) return@LaunchedEffect
                    rejected = true
                    delay(REJECT_SHOWN_MS)
                    rejected = false
                }
                CameraPreview(
                    onScanned = onScanned,
                    accept = accept,
                    onRejected = { rejections++ },
                    modifier = Modifier.fillMaxSize().testTag(QrScanScreenTags.PREVIEW),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                ) {
                    if (rejected) {
                        Text(
                            stringResource(R.string.mobile_not_pairing_code),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(12.dp).testTag(QrScanScreenTags.REJECTED),
                        )
                    } else {
                        Text(stringResource(R.string.scan_hint), modifier = Modifier.padding(12.dp))
                    }
                }
            } else if (asked) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp).testTag(QrScanScreenTags.PERMISSION),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.scan_permission), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.scan_allow)) }
                }
            }
        }
    }
}

/**
 * The back camera's preview and an analysis stream bound to the screen's lifecycle, reading
 * frames until a QR code decodes to text that [accept] takes; [onRejected] runs (on the main
 * thread) for the others. CameraX without camera-view, whose camera-video dependency brings
 * media3 and Guava (docs/DECISIONS.md).
 */
@Composable
private fun CameraPreview(
    onScanned: (String) -> Unit,
    accept: (String) -> Boolean,
    onRejected: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val currentAccept by rememberUpdatedState(accept)
    val currentOnRejected by rememberUpdatedState(onRejected)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val reader = FrameReader()
        val done = AtomicBoolean(false)
        var lastRejectedAt = 0L
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider { request -> surfaceRequest = request } }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(executor) { image ->
                    image.use {
                        if (done.get()) return@use
                        val text = reader.read(it) ?: return@use
                        if (!currentAccept(text)) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastRejectedAt >= REJECT_REPEAT_MS) {
                                lastRejectedAt = now
                                mainExecutor.execute { currentOnRejected() }
                            }
                            return@use
                        }
                        if (done.compareAndSet(false, true)) mainExecutor.execute { currentOnScanned(text) }
                    }
                }
            }
        var provider: ProcessCameraProvider? = null
        try {
            provider = ProcessCameraProvider.awaitInstance(context).also {
                it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // No back camera, or the camera could not be opened.
            unavailable = true
        } finally {
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }
    Box(modifier) {
        surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
        if (unavailable) {
            Text(
                stringResource(R.string.scan_no_camera),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}

/**
 * Reads the QR code in a frame's Y plane, copied into one buffer reused across frames (a new
 * one only when the frame size changes). Not thread-safe; one per analyzer thread.
 */
private class FrameReader {
    private val decoder = QrDecoder()
    private var luma = ByteArray(0)

    fun read(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val stride = plane.rowStride
        val size = stride * image.height
        if (luma.size != size) luma = ByteArray(size)
        // The last row may be shorter than the stride; the bytes past it are never read.
        buffer.get(luma, 0, minOf(buffer.remaining(), size))
        return decoder.decode(luma, image.width, image.height, stride)
    }
}
