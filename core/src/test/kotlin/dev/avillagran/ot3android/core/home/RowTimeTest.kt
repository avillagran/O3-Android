package dev.avillagran.ot3android.core.home

import dev.avillagran.ot3android.core.home.RowTime.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class RowTimeTest {

    private val now = 1_800_000_000_000L
    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun elapsedWhileWorking() {
        assertEquals("0s", RowTimes.elapsed(now, now).text("now"))
        assertEquals("12s", RowTimes.elapsed(now - 12 * second, now).text("now"))
        assertEquals("59s", RowTimes.elapsed(now - 59_999, now).text("now"))
        assertEquals("1m 00s", RowTimes.elapsed(now - minute, now).text("now"))
        assertEquals("3m 05s", RowTimes.elapsed(now - 3 * minute - 5 * second, now).text("now"))
        assertEquals("ot3 keeps counting minutes past the hour", "75m 30s", RowTimes.elapsed(now - 75 * minute - 30 * second, now).text("now"))
        assertEquals("a start in the future is 0s", "0s", RowTimes.elapsed(now + minute, now).text("now"))
    }

    @Test
    fun snoozeCountdownRoundsUp() {
        assertEquals(RowTime.Now, RowTimes.countdown(now, now))
        assertEquals(RowTime.Now, RowTimes.countdown(now - minute, now))
        assertEquals("at least a minute", RowTime.Countdown(1, TimeUnit.Minute), RowTimes.countdown(now + 5 * second, now))
        assertEquals(RowTime.Countdown(2, TimeUnit.Minute), RowTimes.countdown(now + minute + second, now))
        assertEquals(RowTime.Countdown(60, TimeUnit.Minute), RowTimes.countdown(now + hour - second, now))
        assertEquals(RowTime.Countdown(1, TimeUnit.Hour), RowTimes.countdown(now + hour, now))
        assertEquals(RowTime.Countdown(3, TimeUnit.Hour), RowTimes.countdown(now + 2 * hour + minute, now))
        assertEquals(RowTime.Countdown(1, TimeUnit.Day), RowTimes.countdown(now + day, now))
        assertEquals(RowTime.Countdown(2, TimeUnit.Day), RowTimes.countdown(now + day + second, now))
        assertEquals("3h", RowTimes.countdown(now + 2 * hour + minute, now).text("now"))
    }

    @Test
    fun ageRoundsDown() {
        assertEquals(RowTime.None, RowTimes.age(null, now))
        assertEquals(RowTime.None, RowTimes.age(0, now))
        assertEquals(RowTime.Now, RowTimes.age(now - 59 * second, now))
        assertEquals("a time in the future is now", RowTime.Now, RowTimes.age(now + hour, now))
        assertEquals(RowTime.Age(1, TimeUnit.Minute), RowTimes.age(now - minute, now))
        assertEquals(RowTime.Age(59, TimeUnit.Minute), RowTimes.age(now - hour + second, now))
        assertEquals(RowTime.Age(1, TimeUnit.Hour), RowTimes.age(now - hour, now))
        assertEquals(RowTime.Age(23, TimeUnit.Hour), RowTimes.age(now - day + second, now))
        assertEquals(RowTime.Age(1, TimeUnit.Day), RowTimes.age(now - day, now))
        assertEquals(RowTime.Age(40, TimeUnit.Day), RowTimes.age(now - 40 * day, now))
        assertEquals("ahora", RowTimes.age(now, now).text("ahora"))
        assertEquals("5m", RowTimes.age(now - 5 * minute, now).text("now"))
        assertEquals("2h", RowTimes.age(now - 2 * hour, now).text("now"))
        assertEquals("3d", RowTimes.age(now - 3 * day, now).text("now"))
    }

    @Test
    fun workingWinsOverSnoozeWhichWinsOverAge() {
        fun pick(working: Boolean, started: Long?, snoozed: Boolean, until: Long?, changed: Long?) =
            RowTimes.rowTime(now, working, started, snoozed, until, changed)

        assertEquals(RowTime.Elapsed(30), pick(true, now - 30 * second, true, now + hour, now - day))
        assertEquals("working without a start shows nothing", RowTime.None, pick(true, null, false, null, now - day))
        assertEquals(RowTime.Countdown(1, TimeUnit.Hour), pick(false, null, true, now + hour, now - day))
        assertEquals("snoozed without a time falls back to the age", RowTime.Age(1, TimeUnit.Day), pick(false, null, true, null, now - day))
        assertEquals(RowTime.Age(1, TimeUnit.Day), pick(false, null, false, null, now - day))
    }
}
