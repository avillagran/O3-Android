package dev.avillagran.ot3android.core.domain

/**
 * A T3 Code server the user can connect to.
 *
 * Routes beyond URL + token (pairing link, OmaBridge, Tailscale) are added in later
 * iterations; credentials never live in this type.
 *
 * @property id local, stable id of this entry; also the key of its token in the
 *   credential store.
 * @property baseUrl origin only (scheme, host and port), no path.
 * @property environmentId the T3 environment UUID from the server descriptor, once known.
 * @property createdAt epoch milliseconds.
 * @property lastConnectedAt epoch milliseconds of the last successful connection.
 * @property sortOrder position in the user's list, ascending.
 */
data class Environment(
    val id: String,
    val label: String,
    val baseUrl: String,
    val environmentId: String? = null,
    val createdAt: Long,
    val lastConnectedAt: Long? = null,
    val sortOrder: Int = 0,
)
