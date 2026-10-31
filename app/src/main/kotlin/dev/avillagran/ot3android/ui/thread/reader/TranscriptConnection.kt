package dev.avillagran.ot3android.ui.thread.reader

import dev.avillagran.ot3android.core.home.ThreadManagement
import dev.avillagran.ot3android.core.transport.T3Transport
import org.json.JSONObject

/** Authorize once per private answer and recheck ownership after the suspending read. */
internal fun scopedTranscriptSecrets(transport: T3Transport, threadId: String, isCurrent: () -> Boolean): TranscriptSecrets {
    val guarded = object : T3Transport by transport {
        override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
            check(fn == "secrets.answerRequest" && args.optString("threadId") == threadId)
            check(isCurrent())
            check(ThreadManagement.access(JSONObject(), transport.authSession()).canWrite)
            check(isCurrent())
            return transport.call(fn, args, timeoutMs)
        }
    }
    return TranscriptSecrets(guarded)
}
