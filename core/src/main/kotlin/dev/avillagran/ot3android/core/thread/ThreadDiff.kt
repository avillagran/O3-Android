package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.T3Transport
import org.json.JSONObject
import java.util.UUID

/** Read-only typed RPC; Rust validates scope/range and parses the patch. */
object ThreadDiff {
    suspend fun read(
        transport: T3Transport,
        environment: String,
        threadId: String,
        summary: JSONObject,
        ignoreWhitespace: Boolean,
        domain: DomainCalls = DomainEngine.shared,
    ): JSONObject? {
        val captured = JSONObject(summary.toString())
        val expected = JSONObject().put("requestId", UUID.randomUUID().toString())
            .put("environment", environment).put("thread", threadId)
            .put("fromTurnCount", captured.get("fromTurnCount"))
            .put("toTurnCount", captured.get("toTurnCount"))
        val args = JSONObject().put("threadId", threadId)
            .put("fromTurnCount", captured.get("fromTurnCount"))
            .put("toTurnCount", captured.get("toTurnCount"))
            .put("ignoreWhitespace", ignoreWhitespace)
        val result = transport.call("orchestration.getTurnDiff", args)
        val receipt = JSONObject(expected.toString()).put("result", result)
        return domain.domain("diffReview.scopedResult", JSONObject()
            .put("expected", expected).put("receipt", receipt).put("summary", captured)) as? JSONObject
    }
}
