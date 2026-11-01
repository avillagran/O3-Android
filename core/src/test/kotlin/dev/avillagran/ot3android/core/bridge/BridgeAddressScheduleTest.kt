package dev.avillagran.ot3android.core.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BridgeAddressScheduleTest {
    private var now = 0L
    private val schedule = BridgeAddressSchedule({ now }, Random(7))

    @Test
    fun asksOnFirstReachAndThenEveryFewMinutes() {
        assertTrue(schedule.due(catalogAvailable = true))
        schedule.answered(reachable = true)
        assertFalse(schedule.due(catalogAvailable = true))

        now += BridgeAddressSchedule.PERIOD_MS / 2 - 1
        assertFalse(schedule.due(catalogAvailable = true))
        now += BridgeAddressSchedule.PERIOD_MS / 2 + 1
        assertTrue(schedule.due(catalogAvailable = true))
    }

    @Test
    fun aBridgeThatComesBackIsAskedAtOnce() {
        assertTrue(schedule.due(catalogAvailable = false))
        schedule.answered(reachable = false)
        assertFalse(schedule.due(catalogAvailable = false))
        assertTrue("no wait for the backoff", schedule.due(catalogAvailable = true))
        schedule.answered(reachable = true)
        assertFalse(schedule.due(catalogAvailable = true))
    }

    @Test
    fun anUnreachableBridgeIsAskedOnABoundedBackoff() {
        val waits = (0 until 8).map {
            schedule.due(catalogAvailable = false)
            schedule.answered(reachable = false)
            var wait = 0L
            while (!schedule.due(catalogAvailable = false)) {
                now += 1_000
                wait += 1_000
            }
            wait
        }
        assertTrue("was $waits", waits.first() in 15_000..31_000)
        assertTrue("was $waits", waits.all { it <= BridgeAddressSchedule.PERIOD_MS + 1_000 })
        assertTrue("was $waits", waits.last() > waits.first())
    }

    @Test
    fun aNetworkChangeAsksOnTheNextPoll() {
        schedule.due(catalogAvailable = true)
        schedule.answered(reachable = true)
        schedule.wake()
        assertTrue(schedule.due(catalogAvailable = true))
    }
}

class RouteFallbackPlanTest {
    @Test
    fun readsThePlanAndToleratesAnOlderSdk() {
        val rows = JSONArray().put(JSONObject().put("id", "lan").put("baseUrl", "https://gateway.example/"))
        assertEquals(mapOf("lan" to "https://gateway.example/"), routeFallbacks(JSONObject().put("routeFallbacks", rows)))
        assertEquals(emptyMap<String, String>(), routeFallbacks(JSONObject()))
    }
}
