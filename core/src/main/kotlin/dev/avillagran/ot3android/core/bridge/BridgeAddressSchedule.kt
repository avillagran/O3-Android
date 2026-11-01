package dev.avillagran.ot3android.core.bridge

import dev.avillagran.ot3android.core.connection.Backoff
import kotlin.random.Random

/**
 * When to ask the bridge for its current address (`hostRefresh`). A phone can only learn that
 * a bridge moved while it still reaches it, so this asks as soon as the bridge comes back and
 * then every few minutes. While the bridge is out of reach it keeps asking on a jittered
 * backoff, because each ask can hold the SDK's serialized calls for its 8 s deadline. Phones
 * change networks in bursts, so nothing but the user asks twice within [MIN_INTERVAL_MS].
 */
internal class BridgeAddressSchedule(
    private val clock: () -> Long,
    private val random: Random = Random.Default,
) {
    private val failures = Backoff(random, baseMs = FIRST_RETRY_MS, capMs = PERIOD_MS)
    private var available = false
    @Volatile private var nextAt = 0L
    @Volatile private var forced = false
    private var askedAt: Long? = null

    /** Call once per catalog poll, with whether that poll reached the bridge. True means ask now. */
    fun due(catalogAvailable: Boolean): Boolean {
        if (catalogAvailable && !available) nextAt = 0
        available = catalogAvailable
        val now = clock()
        val rested = askedAt.let { it == null || now - it >= MIN_INTERVAL_MS }
        val ask = forced || (rested && now >= nextAt)
        if (ask) {
            forced = false
            askedAt = now
        }
        return ask
    }

    fun answered(reachable: Boolean) {
        nextAt = clock() + if (reachable) {
            failures.reset()
            PERIOD_MS / 2 + random.nextLong(PERIOD_MS / 2 + 1)
        } else {
            failures.nextDelayMs()
        }
    }

    /** The network changed: the next poll past [MIN_INTERVAL_MS] asks. */
    fun wake() {
        nextAt = 0
    }

    /** The user asked: the next poll asks, whenever the last ask was. */
    fun force() {
        forced = true
    }

    companion object {
        const val FIRST_RETRY_MS = 30_000L
        const val PERIOD_MS = 300_000L

        /** Under [FIRST_RETRY_MS]'s jitter, so it never lengthens the backoff. */
        const val MIN_INTERVAL_MS = 15_000L
    }
}
