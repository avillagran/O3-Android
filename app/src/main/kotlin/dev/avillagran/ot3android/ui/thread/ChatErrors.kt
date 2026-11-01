package dev.avillagran.ot3android.ui.thread

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.common.connectionErrorText
import dev.avillagran.t3.T3Exception

/** Failed without a stable code; its reason carries the exception's own message. */
internal const val CHAT_ACTION_FAILED = "chat_action_failed"
private const val REASON_MAX = 200
private val STABLE_CODE = Regex("[a-z][a-z0-9_]{0,63}")

/** Keeps what actually failed: a host code, a `check`/`error` code, or the exception's message. */
internal fun chatFailure(error: Exception): ThreadNotice.Failed {
    if (error is T3Exception) return ThreadNotice.Failed(error.code, error.reason, error.requiredScope)
    val message = error.message?.takeIf(String::isNotBlank)
    return if (message != null && STABLE_CODE.matches(message)) ThreadNotice.Failed(message)
    else ThreadNotice.Failed(CHAT_ACTION_FAILED, (message ?: error.javaClass.simpleName).take(REASON_MAX))
}

@StringRes
internal fun chatErrorMessage(code: String): Int? = when (code) {
    "connection_changed" -> R.string.chat_error_connection_changed
    "thread_unavailable" -> R.string.chat_error_thread_unavailable
    "run_unavailable" -> R.string.chat_error_run_unavailable
    "plan_unavailable", "plan_scope_changed" -> R.string.chat_error_plan_unavailable
    "new_thread_recipe_unavailable", "project_unavailable", "launch_identity_mismatch" -> R.string.chat_error_new_thread_unavailable
    "composer_changed" -> R.string.chat_error_composer_changed
    "thread_is_working" -> R.string.chat_error_thread_working
    "attachment_authority_unavailable", "attachment_authority_changed" -> R.string.chat_error_attachment_access
    "attachment_storage_unavailable" -> R.string.chat_error_storage
    else -> null
}

/** A failure of something the user did in a thread; "could not connect" only for connection codes. */
@Composable
internal fun chatErrorText(failure: ThreadNotice.Failed): String {
    val detail = if (failure.code == CHAT_ACTION_FAILED) failure.reason ?: failure.code else failure.code
    return chatErrorMessage(failure.code)?.let { stringResource(it) }
        ?: connectionErrorText(failure.code)
        ?: stringResource(R.string.chat_error_action_failed, detail)
}
