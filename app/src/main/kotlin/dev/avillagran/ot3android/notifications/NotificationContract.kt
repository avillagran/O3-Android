package dev.avillagran.ot3android.notifications

import org.json.JSONArray
import org.json.JSONObject

/*
 * The data a notification shows, as the lib will return it (LIB_REQUESTS.md, "UI-N: thread
 * awareness"; UI_SPEC "Notifications and Live Updates"). Kotlin reads these objects and
 * builds Android notifications from them. It never derives a phase, a count, a transition or
 * an expiry: every such value arrives here already decided by awareness.view in v0.3.5.
 */

/** T3's agent awareness phase (`t3:packages/shared/src/agentAwareness.ts:11-18`). */
enum class AwarenessPhase(val wire: String) {
    Starting("starting"),
    Running("running"),
    Approval("waiting_for_approval"),
    Input("waiting_for_input"),
    Stale("stale"),
    Completed("completed"),
    Failed("failed"),
    ;

    val needsUser: Boolean get() = this == Approval || this == Input
    val finished: Boolean get() = this == Completed || this == Failed

    companion object {
        fun fromWire(value: String?): AwarenessPhase? = entries.firstOrNull { it.wire == value }
    }
}

/** A thread as the app routes to it: the ids [ThreadDeepLink][dev.avillagran.ot3android.ui.navigation.ThreadDeepLink] takes. */
data class ThreadRef(val environmentId: String, val threadId: String)

/** One line of the expanded card, in the lib's order (priority first). */
data class AwarenessRow(
    val thread: ThreadRef,
    val phase: AwarenessPhase,
    val title: String,
    /** The project's title. */
    val project: String,
    /** The environment's display name. */
    val host: String,
)

/** What the status bar chip says; the lib picks the kind and the count. */
enum class ChipKind(val wire: String) {
    Working("working"),
    Live("live"),
    Approve("approve"),
    Answer("answer"),
    ;

    companion object {
        fun fromWire(value: String?): ChipKind? = entries.firstOrNull { it.wire == value }
    }
}

data class Chip(val kind: ChipKind, val count: Int)

/** The single ongoing card (`awareness.card`). */
data class AwarenessCard(
    /** Work is live: the card is ongoing and asks to be promoted. False for Done and Failed. */
    val active: Boolean,
    /** The priority phase (the first row's). */
    val phase: AwarenessPhase,
    /** One thread: its title. Several: the lib's summary ("2 need you", ...). */
    val title: String,
    /** `project · host` for one thread, or the lib's subtitle for several. */
    val subtitle: String,
    /** The status label in the user's language, as the lib returns it. */
    val status: String,
    /** Null when the card is not active: finished cards carry no chip. */
    val chip: Chip?,
    /** When the current phase began (epoch ms); null hides the timer. */
    val since: Long?,
    /** Absolute deadline (epoch ms); a replay never extends it. */
    val expiresAt: Long?,
    /** Where a tap goes: the priority thread. */
    val route: ThreadRef,
    val rows: List<AwarenessRow>,
)

/** T3's two alert groups: needs-you transitions and finished transitions. */
enum class AlertGroup(val wire: String) {
    Attention("attention"),
    Finished("finished"),
    ;

    companion object {
        fun fromWire(value: String?): AlertGroup? = entries.firstOrNull { it.wire == value }
    }
}

/** One alert the lib decided to raise (`awareness.transitions`). */
data class AwarenessAlert(
    /** Stable over retries and reconnects; the posted notification's tag. */
    val id: String,
    val group: AlertGroup,
    val phase: AwarenessPhase,
    val title: String,
    val body: String,
    val route: ThreadRef,
    /** Optional absolute deadline for Done / Failed alerts. */
    val expiresAt: Long?,
    val status: String = "",
)

/** Reads the lib's objects. Anything malformed is dropped, never guessed. */
object NotificationContract {
    /** Longest title or body handed to Android; the lib truncates first, this only guards. */
    const val MAX_TEXT = 240

    fun card(json: JSONObject?): AwarenessCard? {
        json ?: return null
        val phase = AwarenessPhase.fromWire(json.optString("phase")) ?: return null
        val route = ref(json.optJSONObject("route")) ?: return null
        val expiresAt = if (json.has("expiresAt")) json.optLong("expiresAt", 0L).takeIf { it > 0 } ?: return null else null
        val active = json.optBoolean("active", false)
        val chip = json.optJSONObject("chip")?.let { c ->
            ChipKind.fromWire(c.optString("kind"))?.let { Chip(it, c.optInt("count", 1).coerceAtLeast(1)) }
        }
        return AwarenessCard(
            active = active,
            phase = phase,
            title = text(json, "title"),
            subtitle = text(json, "subtitle"),
            status = text(json, "status"),
            chip = chip.takeIf { active },
            since = json.optLong("since", 0L).takeIf { it > 0 },
            expiresAt = expiresAt,
            route = route,
            rows = rows(json.optJSONArray("rows")),
        )
    }

    fun alert(json: JSONObject?): AwarenessAlert? {
        json ?: return null
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val group = AlertGroup.fromWire(json.optString("group")) ?: return null
        val phase = AwarenessPhase.fromWire(json.optString("phase")) ?: return null
        val route = ref(json.optJSONObject("route")) ?: return null
        return AwarenessAlert(
            id = id,
            group = group,
            phase = phase,
            title = text(json, "title"),
            body = text(json, "body"),
            status = text(json, "status"),
            route = route,
            expiresAt = json.optLong("expiresAt", 0L).takeIf { it > 0 },
        )
    }

    private fun rows(array: JSONArray?): List<AwarenessRow> = buildList {
        for (i in 0 until (array?.length() ?: 0)) {
            val row = array!!.optJSONObject(i) ?: continue
            val phase = AwarenessPhase.fromWire(row.optString("phase")) ?: continue
            val thread = ref(row.optJSONObject("route")) ?: continue
            add(AwarenessRow(thread, phase, text(row, "title"), text(row, "project"), text(row, "host")))
        }
    }

    private fun ref(json: JSONObject?): ThreadRef? {
        val environment = json?.optString("environmentId")?.takeIf { it.isNotBlank() } ?: return null
        val thread = json.optString("threadId").takeIf { it.isNotBlank() } ?: return null
        return ThreadRef(environment, thread)
    }

    private fun text(json: JSONObject, key: String): String =
        json.optString(key).replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)
}
