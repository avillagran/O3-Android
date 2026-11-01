package dev.avillagran.ot3android.core.bridge

import dev.avillagran.ot3android.core.connection.Backoff
import kotlin.random.Random

/**
 * When to ask the bridge for its current address (`hostRefresh`). A phone can only learn that
 * a bridge moved while it still reaches it, so this asks as soon as the bridge comes back and
 * then every few minutes. While the bridge is out of reach it keeps asking on a jittered
 * backoff, because each ask can hold the SDK's serialized calls for its 8 s deadline.
 */
internal class BridgeAddressSchedule(
    private val clock: () -> Long,
    private val random: Random = Random.Default,
) {
    private val failures = Backoff(random, baseMs = FIRST_RETRY_MS, capMs = PERIOD_MS)
    private var available = false
    @Volatile private var nextAt = 0L

    /** Call once per catalog poll, with whether that poll reached the bridge. */
    fun due(catalogAvailable: Boolean): Boolean {
        val recovered = catalogAvailable && !available
        available = catalogAvailable
        return recovered || clock() >= nextAt
    }

    fun answered(reachable: Boolean) {
        nextAt = clock() + if (reachable) {
            failures.reset()
            PERIOD_MS / 2 + random.nextLong(PERIOD_MS / 2 + 1)
        } else {
            failures.nextDelayMs()
        }
    }

    /** The network changed or the user asked: the next poll asks too. */
    fun wake() {
        nextAt = 0
    }

    companion object {
        const val FIRST_RETRY_MS = 30_000L
        const val PERIOD_MS = 300_000L
    }
}
