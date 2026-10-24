package dev.avillagran.ot3android.core.domain

/**
 * A T3 Code server the user can connect to.
 *
 * Routes (pairing link, URL + token, OmaBridge, Tailscale) and credentials are added
 * in later iterations; credentials never live in this type.
 */
data class Environment(
    val id: String,
    val label: String,
    val baseUrl: String,
)
