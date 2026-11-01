package dev.avillagran.ot3android.ui.thread.composer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.ot3android.core.composer.ComposerSnapshot
import java.io.File

/** A result is accepted only by the editor that started capture, before any intervening edit. */
internal fun captureMatches(origin: ComposerSnapshot, current: ComposerSnapshot): Boolean =
    origin.scope == current.scope && origin.version == current.version && origin.text == current.text && origin.attachments == current.attachments && current.transformable

internal class CapturePhotoProvider : FileProvider()

/** Capture stays phone-side. Successful files enter the same bounded native upload as the picker. */
@Composable
internal fun PhoneCapture(snapshot: ComposerSnapshot, onRequest: (ComposerRequest) -> Unit,
    compact: Boolean = false, actionsOpen: Boolean = false, onDismissActions: () -> Unit = {},
) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(snapshot)
    val sendRequest by rememberUpdatedState(onRequest)
    var error by remember(snapshot.scope) { mutableStateOf(false) }
    // Origins are saved: the camera can rotate the display, which recreates this Activity.
    var photoOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    var imageOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val origin = imageOrigin; imageOrigin = null
        if (uri != null && origin != null) {
            if (origin == latest.scope.toString()) sendRequest(ComposerRequest.ImportUri(latest, uri.toString()))
            else error = true
        }
    }
    fun deletePhoto() { photoUri?.let { runCatching { context.contentResolver.delete(android.net.Uri.parse(it), null, null) } }; photoUri = null; photoOrigin = null }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = photoUri; val origin = photoOrigin
        if (success && uri != null && origin == latest.scope.toString()) {
            // The import owner keeps the capture until its bytes are spooled or the chip is removed.
            sendRequest(ComposerRequest.ImportUri(latest, uri))
            photoUri = null; photoOrigin = null
        } else { deletePhoto(); if (success) error = true }
    }
    fun takePhoto() {
        try {
            val directory = File(context.cacheDir, "phone-captures").apply { mkdirs() }
            // Orphans from process death are never restored or uploaded automatically.
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
            val file = File.createTempFile("photo-", ".jpg", directory)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.phone-capture", file)
            photoUri = uri.toString()
            camera.launch(uri)
        } catch (_: Exception) { deletePhoto(); error = true }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && photoOrigin == latest.scope.toString() && latest.transformable) takePhoto()
        else { deletePhoto(); error = true }
    }
    var speechOpen by remember(snapshot.scope) { mutableStateOf(false) }
    var speechOrigin by remember { mutableStateOf<ComposerSnapshot?>(null) }
    val buttons: @Composable () -> Unit = {
        Ot3IconButton("mic", stringResource(R.string.capture_dictate), enabled = snapshot.transformable, onClick = { onDismissActions(); error = false; speechOrigin = snapshot; speechOpen = true }, modifier = Modifier.testTag("capture_dictate"))
        Ot3IconButton("image", stringResource(R.string.capture_image), enabled = snapshot.transformable && photoOrigin == null, onClick = {
            onDismissActions(); error = false; imageOrigin = snapshot.scope.toString()
            try { imagePicker.launch("image/*") } catch (_: Exception) { imageOrigin = null; error = true }
        }, modifier = Modifier.testTag("capture_image"))
        Ot3IconButton("camera", stringResource(R.string.capture_photo), enabled = snapshot.transformable && photoOrigin == null, onClick = {
            onDismissActions(); error = false; photoOrigin = snapshot.scope.toString()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) takePhoto()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }, modifier = Modifier.testTag("capture_photo"))
    }
    if (compact) DropdownMenu(expanded = actionsOpen, onDismissRequest = onDismissActions) { buttons() }
    else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { buttons() }
    if (error) Text(stringResource(R.string.capture_failed), color = MaterialTheme.colorScheme.error)
    if (speechOpen) SpeechCaptureDialog(onClose = { speechOpen = false }, onInsert = { text ->
        val origin = speechOrigin
        if (origin != null && captureMatches(origin, latest)) {
            sendRequest(ComposerRequest.CaptureText(origin, text)); speechOpen = false; true
        } else { error = true; false }
    })
}

/** No audio files or partial transcripts are persisted. Cancellation invalidates all late callbacks. */
@Composable
private fun SpeechCaptureDialog(onClose: () -> Unit, onInsert: (String) -> Boolean) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val session = remember {
        DictationSession(create = { AndroidDictationEngine(context) }, changed = { revision++ })
    }
    @Suppress("UNUSED_VARIABLE") val observedRevision = revision
    val listening = session.listening
    val failed = session.failed
    var text by remember { mutableStateOf("") }
    var insertionFailed by remember { mutableStateOf(false) }
    LaunchedEffect(session.text) { text = session.text }
    fun cancel() = session.cancel()
    fun start() { text = ""; session.start() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) start() else session.permissionDenied() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel() }
    }
    LaunchedEffect(listening) { if (listening) { kotlinx.coroutines.delay(60_000); session.timeout() } }
    AlertDialog(onDismissRequest = { cancel(); onClose() }, title = { Text(stringResource(R.string.capture_dictate)) },
        text = { Column {
            Text(stringResource(R.string.capture_speech_consent))
            if (listening) Text(stringResource(R.string.capture_listening))
            if (failed || insertionFailed) Text(stringResource(R.string.capture_failed), color = MaterialTheme.colorScheme.error)
            if (text.isNotEmpty()) OutlinedTextField(value = text, onValueChange = { text = it.take(16_384) }, label = { Text(stringResource(R.string.capture_review)) })
            TextButton(enabled = !listening, onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(stringResource(R.string.capture_start)) }
        } },
        confirmButton = { TextButton(enabled = !listening && text.isNotBlank(), onClick = { insertionFailed = !onInsert(text) }) { Text(stringResource(R.string.capture_insert)) } },
        dismissButton = { TextButton(onClick = { cancel(); onClose() }) { Text(stringResource(R.string.queue_cancel_edit)) } })
}

/** Service callbacks run on Android’s main looper. Prefer the on-device recognizer when present. */
private class AndroidDictationEngine(private val context: android.content.Context) : DictationEngine {
    private var recognizer: SpeechRecognizer? = null
    override fun start(result: (String?) -> Unit, error: () -> Unit) {
        val engine = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = engine
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onError(code: Int) = error()
            override fun onResults(results: Bundle?) = result(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
        })
        engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
    }
    override fun cancel() {
        val old = recognizer; recognizer = null
        try { old?.cancel() } finally { old?.destroy() }
    }
}
