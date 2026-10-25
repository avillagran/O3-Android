package dev.avillagran.ot3android.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffTest {

    /** Always draws the given end of the jitter range. */
    private class Edge(private val high: Boolean) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(until: Long): Long = if (high) until - 1 else 0
    }

    @Test
    fun stepsDoubleFromOneSecondUpToTheCap() {
        val full = Backoff(Edge(high = true))
        val steps = List(12) { full.nextDelayMs() }

        assertEquals(
            listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 64_000, 128_000, 256_000, 300_000, 300_000, 300_000),
            steps,
        )
    }

    @Test
    fun jitterStaysBetweenHalfAndFullStep() {
        val low = Backoff(Edge(high = false))
        assertEquals(listOf(500L, 1_000, 2_000), List(3) { low.nextDelayMs() })

        val random = Backoff(Random(42))
        repeat(20) { attempt ->
            val step = minOf(1_000L shl minOf(attempt, 20), 300_000)
            val delay = random.nextDelayMs()
            assertTrue("attempt $attempt: $delay", delay in step / 2..step)
        }
    }

    @Test
    fun aConnectionOfThirtySecondsResetsTheCurve() {
        val backoff = Backoff(Edge(high = true))
        repeat(4) { backoff.nextDelayMs() }

        backoff.connectionLasted(29_999)
        assertEquals(16_000L, backoff.nextDelayMs())

        backoff.connectionLasted(30_000)
        assertEquals(0, backoff.failures)
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test
    fun manyFailuresDoNotOverflow() {
        val backoff = Backoff(Edge(high = true))
        repeat(200) { backoff.nextDelayMs() }
        assertEquals(300_000L, backoff.nextDelayMs())
    }
}
