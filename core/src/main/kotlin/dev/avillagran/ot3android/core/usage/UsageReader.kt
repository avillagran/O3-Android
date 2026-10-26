package dev.avillagran.ot3android.core.usage

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.T3Transport
import org.json.JSONObject

/** Android supplies its local date/zone; Rust builds the window and sums the summary. */
object UsageReader {
    suspend fun read(transport: T3Transport, period: String, today: String, timeZone: String, domain: DomainCalls = DomainEngine.shared): JSONObject {
        val request = domain.domain("usageSummary.request", JSONObject()
            .put("period", period).put("today", today).put("timeZone", timeZone)) as JSONObject
        val summary = transport.call("server.getUsageSummary", request.getJSONObject("payload"))
        return domain.domain("usageSummary.aggregate", JSONObject().put("summary", summary)
            .put("sinceDay", request.getString("sinceDay")).put("untilDay", request.getString("untilDay"))) as JSONObject
    }
}
