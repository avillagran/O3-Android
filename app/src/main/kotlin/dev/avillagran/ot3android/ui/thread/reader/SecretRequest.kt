package dev.avillagran.ot3android.ui.thread.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

internal enum class SecretDelivery { Sending, Sent, Uncertain }

/** Direct, non-replayable native call. Never use an outbox or logging adapter. */
internal class TranscriptSecrets(private val transport: T3Transport) {
    // Only outcome metadata survives a row/dialog recreation; never retain the value.
    private val deliveryState = MutableStateFlow<Map<String, SecretDelivery>>(emptyMap())
    val deliveries = deliveryState.asStateFlow()

    suspend fun answer(threadId: String, itemId: String, value: CharArray?) {
        val key = "$threadId/$itemId"
        while (true) {
            val previous = deliveryState.value
            if (key in previous) { value?.fill('\u0000'); return }
            if (deliveryState.compareAndSet(previous, previous + (key to SecretDelivery.Sending))) break
        }
        val answer = JSONObject().put("type", if (value == null) "decline" else "save")
        try {
            if (value != null) answer.put("secret", String(value))
            transport.call("secrets.answerRequest", JSONObject().put("threadId", threadId)
                .put("turnItemId", itemId).put("answer", answer))
            deliveryState.update { it + (key to SecretDelivery.Sent) }
        } catch (failure: Exception) {
            deliveryState.update { it + (key to SecretDelivery.Uncertain) }
            throw failure
        } finally {
            value?.fill('\u0000')
            answer.remove("secret")
        }
    }
}

@Composable
internal fun SecretRequestCard(request: JSONObject, secrets: TranscriptSecrets?) {
    val identity = request.readerText("threadId") + "/" + request.readerText("turnItemId")
    val emptyDeliveries = remember { MutableStateFlow(emptyMap<String, SecretDelivery>()) }
    val deliveries by (secrets?.deliveries ?: emptyDeliveries).collectAsState()
    val delivery = deliveries[identity]
    // Deliberately not saveable: no secret in Bundle, preferences, clipboard or draft.
    var editing by remember(identity) { mutableStateOf(false) }
    var input by remember(identity) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(identity, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { input = ""; editing = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); input = "" }
    }
    val status = request.readerText("status")
    val pending = request.readerText("threadId").isNotBlank() && request.readerText("turnItemId").isNotBlank() &&
        status == "pending" && request.optBoolean("local", true)
    DisposableEffect(identity, pending) { onDispose { input = ""; editing = false } }

    fun submit(decline: Boolean) {
        val target = secrets ?: return
        if (!pending || delivery != null) return
        val value = if (decline) null else input.toCharArray()
        input = ""; editing = false
        // Enter the try/finally before a row disposal can cancel the coroutine.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                target.answer(request.readerText("threadId"), request.readerText("turnItemId"), value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Outcome metadata renders a fixed resource; no upstream error text or replay.
            } finally { value?.fill('\u0000') }
        }
    }

    Column(Modifier.fillMaxWidth().testTag("transcript_secret"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(request.readerText("label"), style = MaterialTheme.typography.titleSmall)
        Text(request.readerText("reason"))
        if (pending && secrets != null && delivery == null) {
            TextButton(onClick = { editing = true }, modifier = Modifier.testTag("transcript_secret_answer")) {
                Text(stringResource(R.string.transcript_secret_save))
            }
            if (editing) Dialog(
                onDismissRequest = { input = ""; editing = false },
                properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            ) {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(input, { input = it }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth().testTag("transcript_secret_input"))
                        Row {
                            TextButton(enabled = input.isNotBlank(), onClick = { submit(false) }, modifier = Modifier.testTag("transcript_secret_save")) {
                                Text(stringResource(R.string.transcript_secret_save))
                            }
                            TextButton(onClick = { submit(true) }, modifier = Modifier.testTag("transcript_secret_decline")) {
                                Text(stringResource(R.string.transcript_secret_decline))
                            }
                        }
                    }
                }
            }
        }
        Text(stringResource(when {
            status == "saved" -> R.string.transcript_secret_saved
            status == "declined" -> R.string.transcript_secret_declined
            status != "pending" -> R.string.transcript_secret_ended
            delivery == SecretDelivery.Uncertain -> R.string.transcript_secret_uncertain
            delivery != null -> R.string.transcript_secret_sent
            pending && secrets != null -> R.string.transcript_secret_private
            else -> R.string.transcript_secret_unavailable
        }))
    }
}
