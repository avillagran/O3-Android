package dev.avillagran.ot3android.core.connection

import java.net.URI
import java.net.URISyntaxException

/** Approved route origin by local environment id. Survives a restart; never leaves the device. */
interface RouteApprovalStore {
    suspend fun all(): Map<String, String>

    /** Replaces the approval of [environmentId], or removes it when [origin] is null. */
    suspend fun set(environmentId: String, origin: String?)
}

class InMemoryRouteApprovals : RouteApprovalStore {
    private val approvals = java.util.concurrent.ConcurrentHashMap<String, String>()
    override suspend fun all(): Map<String, String> = approvals.toMap()
    override suspend fun set(environmentId: String, origin: String?) {
        if (origin == null) approvals.remove(environmentId) else approvals[environmentId] = origin
    }
}

/**
 * The exact origin of an offered route: `https://host:port`, lower case, with the port always
 * written. Approvals are compared on this string, so a change of scheme, host or port asks again.
 * Null for anything the user could not read for what it is: not HTTPS, credentials in the URL, or
 * a host that is not plain ASCII.
 */
fun routeOrigin(url: String): String? {
    val uri = try { URI(url) } catch (_: URISyntaxException) { return null }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() && it.all { char -> char.code < 128 } } ?: return null
    val port = if (uri.port == -1) 443 else uri.port
    return "https://$host:$port"
}

/** What a person reads and approves: the host, and the port unless it is the default. */
fun routeOriginLabel(origin: String): String = origin.removePrefix("https://").removeSuffix(":443")

/** A row's fallback, as the Environments screen shows it. */
sealed interface RouteFallbackView {
    /** The row's own URL is out of reach and [origin] is offered: nothing is sent until the user says so. */
    data class NeedsApproval(val origin: String) : RouteFallbackView

    /** The row is using, or connecting through, the approved [origin]. */
    data class InUse(val origin: String) : RouteFallbackView

    /**
     * The approved [origin] did not take the row: [error] is `unauthorized` for a refused token,
     * or the descriptor's error, in which case no token was sent.
     */
    data class Refused(val origin: String, val error: String) : RouteFallbackView
}

/** Pure, for the UI flow: combines what the catalog offers with what the user approved. */
internal fun routeFallbackView(offered: String?, approved: String?, status: FallbackStatus): RouteFallbackView? {
    status.inUse?.let { url -> routeOrigin(url)?.let { return RouteFallbackView.InUse(it) } }
    status.refused?.let { refusal ->
        routeOrigin(refusal.url)?.let { return RouteFallbackView.Refused(it, refusal.error) }
    }
    val origin = offered?.let(::routeOrigin) ?: return null
    return RouteFallbackView.NeedsApproval(origin).takeIf { status.ownRouteDown && origin != approved }
}
