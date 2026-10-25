package dev.avillagran.ot3android.core.home

/**
 * The time a row shows instead of (or beside) its status, ot3's `timeLabel`
 * (`ot3:ui/qml/T3Panel.qml:541-545,691-696,773`): elapsed while working, the countdown while
 * snoozed, otherwise the age. Pure: [RowTime.text] formats it, [rowTime] picks it.
 */
sealed interface RowTime {
    /** A run is active since [seconds] ago: "12s", "3m 05s". */
    data class Elapsed(val seconds: Long) : RowTime

    /** Snoozed: wakes in [value] [unit]s, rounded up; [Now] once due. */
    data class Countdown(val value: Long, val unit: TimeUnit) : RowTime

    /** [value] [unit]s since the last change, rounded down; [Now] under a minute. */
    data class Age(val value: Long, val unit: TimeUnit) : RowTime

    /** "now": under a minute old, or a snooze that is due. */
    data object Now : RowTime

    /** No timestamp to show. */
    data object None : RowTime

    enum class TimeUnit(val suffix: String) { Minute("m"), Hour("h"), Day("d") }

    /** ot3's text; [now] is the translated "now". */
    fun text(now: String): String = when (this) {
        is Elapsed -> if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
        is Countdown -> "$value${unit.suffix}"
        is Age -> "$value${unit.suffix}"
        Now -> now
        None -> ""
    }
}

object RowTimes {
    private const val MINUTE = 60_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L

    /**
     * Elapsed from [runStartedAt] while [working]; else the countdown to [snoozedUntil] while
     * [snoozed]; else the age of [changedAt] (a settled row passes its settled time).
     */
    fun rowTime(
        now: Long,
        working: Boolean,
        runStartedAt: Long?,
        snoozed: Boolean,
        snoozedUntil: Long?,
        changedAt: Long?,
    ): RowTime = when {
        working -> if (runStartedAt != null && runStartedAt > 0) elapsed(runStartedAt, now) else RowTime.None
        snoozed && snoozedUntil != null -> countdown(snoozedUntil, now)
        else -> age(changedAt, now)
    }

    /** ot3 `duration`: whole seconds since [start], never negative. */
    fun elapsed(start: Long, now: Long): RowTime = RowTime.Elapsed(((now - start) / 1000).coerceAtLeast(0))

    /** ot3 `snoozeRemaining`: minutes (at least 1) under an hour, hours under a day, then days; all rounded up. */
    fun countdown(until: Long, now: Long): RowTime {
        val remaining = until - now
        return when {
            remaining <= 0 -> RowTime.Now
            remaining < HOUR -> RowTime.Countdown(ceilDiv(remaining, MINUTE).coerceAtLeast(1), RowTime.TimeUnit.Minute)
            remaining < DAY -> RowTime.Countdown(ceilDiv(remaining, HOUR), RowTime.TimeUnit.Hour)
            else -> RowTime.Countdown(ceilDiv(remaining, DAY), RowTime.TimeUnit.Day)
        }
    }

    /** ot3 `age`: now under a minute, then minutes, hours and days, rounded down. */
    fun age(at: Long?, now: Long): RowTime {
        if (at == null || at <= 0) return RowTime.None
        val minutes = ((now - at) / MINUTE).coerceAtLeast(0)
        return when {
            minutes < 1 -> RowTime.Now
            minutes < 60 -> RowTime.Age(minutes, RowTime.TimeUnit.Minute)
            minutes < 1440 -> RowTime.Age(minutes / 60, RowTime.TimeUnit.Hour)
            else -> RowTime.Age(minutes / 1440, RowTime.TimeUnit.Day)
        }
    }

    private fun ceilDiv(value: Long, unit: Long): Long = (value + unit - 1) / unit
}
