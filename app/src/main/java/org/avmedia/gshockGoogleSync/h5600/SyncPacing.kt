package org.avmedia.gshockGoogleSync.h5600

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * How often the phone talks to the watch on its own. Every sync wakes the watch's radio, and the
 * battery went from full to half in 2-3 days at quarter-hour full syncs + step syncs, so weather and
 * steps are hourly and stop overnight. The sun keep-alive is 2 small packets every 10 min. Alarm
 * writes, snooze repeats and syncs on connect are not paced.
 */
object SyncPacing {
    /** Weather / sun-data refresh and step sync interval. */
    const val PERIODIC_MIN = 60L

    /**
     * Light sun refresh (location + time, 2 packets) interval, day and night: the watch loses its
     * sun location on its own, and nothing on the watch tells us when.
     */
    const val SUN_KEEP_MIN = 10L

    /** Light sun refresh this long after a full time sync (the watch resets its location ~1-2 min after one). */
    const val AFTER_FULL_SYNC_MIN = 3L

    /** Next light sun refresh after the last sun write at [last]. Pure. */
    fun nextSunKeep(last: LocalDateTime): LocalDateTime = last.plusMinutes(SUN_KEEP_MIN)

    /** No periodic weather / step syncs from [QUIET_START] (inclusive) to [QUIET_END] (exclusive). */
    val QUIET_START: LocalTime = LocalTime.MIDNIGHT
    val QUIET_END: LocalTime = LocalTime.of(6, 0)

    fun isQuiet(now: LocalDateTime): Boolean {
        val t = now.toLocalTime()
        return !t.isBefore(QUIET_START) && t.isBefore(QUIET_END)
    }

    /**
     * Next periodic sync: 1 minute past the next full hour (Open-Meteo publishes on the quarter
     * hours, so :01 picks up a fresh value), moved to [QUIET_END] + 1 min if that falls in the
     * quiet hours. Pure.
     */
    fun nextPeriodic(now: LocalDateTime): LocalDateTime {
        var next = now.withMinute(1).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusHours(1)
        if (isQuiet(next)) next = next.with(QUIET_END).plusMinutes(1)
        return next
    }
}
