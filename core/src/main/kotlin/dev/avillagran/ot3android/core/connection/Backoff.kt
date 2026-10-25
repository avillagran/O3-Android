package dev.avillagran.ot3android.core.connection

import kotlin.random.Random

/**
 * Jittered exponential backoff for reconnecting, the same curve as ot3: [baseMs] doubling per
 * failure up to [capMs], each delay drawn uniformly from [half, full] of that step. A
 * connection that stayed up for [resetAfterMs] starts over from [baseMs].
 *
 * The lib has the same policy in Rust (`reconnect::Backoff`) but does not expose it yet
 * (docs/LIB_REQUESTS.md, phase 1 item 5). Not thread-safe: one per reconnect loop.
 */
class Backoff(
    private val random: Random = Random.Default,
    private val baseMs: Long = 1_000,
    private val capMs: Long = 300_000,
    private val resetAfterMs: Long = 30_000,
) {
    /** Failures since the last reset. */
    var failures: Int = 0
        private set

    /** The delay before the next attempt; counts one more failure. */
    fun nextDelayMs(): Long {
        val step = stepMs(failures)
        failures++
        val half = step / 2
        return half + random.nextLong(step - half + 1)
    }

    /** Call when a connection ends; one that lasted at least [resetAfterMs] resets the curve. */
    fun connectionLasted(ms: Long) {
        if (ms >= resetAfterMs) reset()
    }

    fun reset() {
        failures = 0
    }

    private fun stepMs(failures: Int): Long {
        var step = baseMs
        repeat(failures) {
            if (step >= capMs) return capMs
            step *= 2
        }
        return step.coerceAtMost(capMs)
    }
}
