package dev.avillagran.ot3android.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import kotlinx.coroutines.delay

/**
 * User-facing text for a lib error code (`T3Exception.code`). Codes are stable identifiers
 * and never carry credentials, so the generic case may show one.
 */
@Composable
fun errorText(code: String): String = connectionErrorText(code) ?: stringResource(R.string.connect_error_generic, code)

/** The message of a connection error code; null for a code that is not about connecting. */
@Composable
fun connectionErrorText(code: String): String? = when {
    code == "invalid_endpoint" -> stringResource(R.string.connect_error_invalid_endpoint)
    code == "unreachable" || code == "rpc_connection_failed" -> stringResource(R.string.connect_error_unreachable)
    code == "unauthorized" -> stringResource(R.string.connect_error_unauthorized)
    code == "not_json" -> stringResource(R.string.connect_error_not_json)
    code == "t3_environment_mismatch" -> stringResource(R.string.connect_error_mismatch)
    code == ConnectionSupervisor.MISSING_TOKEN -> stringResource(R.string.connect_error_missing_token)
    code == ConnectionSupervisor.STREAM_CLOSED -> stringResource(R.string.connect_error_stream_closed)
    code == "not_connected" -> stringResource(R.string.connect_error_not_connected)
    code == "subscription_limit" -> stringResource(R.string.connect_error_subscription_limit)
    code == ConnectionSupervisor.INTERNAL_ERROR -> stringResource(R.string.connect_error_internal)
    code.startsWith("http_") -> stringResource(R.string.connect_error_http, code.removePrefix("http_"))
    else -> null
}

/**
 * True when only the user can fix the connection: a rejected token, or an error the
 * supervisor will not retry (no token, another server, a bad address). Offer "Edit" then.
 */
fun ConnectionState?.needsEdit(): Boolean =
    this == ConnectionState.Unauthorized || (this is ConnectionState.Unreachable && nextRetryAt == null)

/** Short label of a connection state; counts down to the next retry. */
@Composable
fun connectionStateText(state: ConnectionState?, now: () -> Long = System::currentTimeMillis): String = when (state) {
    null, ConnectionState.Disconnected -> stringResource(R.string.state_disconnected)
    ConnectionState.Connecting -> stringResource(R.string.state_connecting)
    is ConnectionState.Connected -> stringResource(R.string.state_connected)
    ConnectionState.Unauthorized -> stringResource(R.string.state_unauthorized)
    is ConnectionState.Unreachable -> {
        val retryAt = state.nextRetryAt
        // A new retry time restarts the countdown from now, not from the previous one's clock.
        var clock by remember(retryAt) { mutableLongStateOf(now()) }
        LaunchedEffect(retryAt) {
            while (retryAt != null && clock < retryAt) {
                delay(1_000)
                clock = now()
            }
        }
        val seconds = retryAt?.let { ((it - clock + 999) / 1000).coerceAtLeast(0).toInt() }
        val transient = state.error in TRANSIENT_DISPLAY
        when {
            transient && seconds != null -> stringResource(R.string.state_unreachable_retry, seconds)
            transient -> stringResource(R.string.state_unreachable)
            seconds != null -> stringResource(R.string.state_error_retry, errorText(state.error), seconds)
            else -> errorText(state.error)
        }
    }
}

private val TRANSIENT_DISPLAY = setOf("unreachable", "stream_timeout", "disconnected", "rpc_connection_failed")

/** A small colored label for a connection state. */
@Composable
fun ConnectionChip(state: ConnectionState?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        is ConnectionState.Connected -> colors.primaryContainer to colors.onPrimaryContainer
        ConnectionState.Unauthorized, is ConnectionState.Unreachable -> colors.errorContainer to colors.onErrorContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    StatusLabel(connectionStateText(state), container, content, modifier.testTag(CommonTags.CONNECTION_CHIP))
}

@Composable
fun StatusLabel(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

object CommonTags {
    const val CONNECTION_CHIP = "connection_chip"
}
