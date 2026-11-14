package com.kronokeep.app.domain.reminders

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

object ReminderTiming {
    /** Local time the daily reminder job aims for. WorkManager may run it a little later. */
    val DAILY_AT: LocalTime = LocalTime.of(8, 0)

    /** Time from [now] until the next [at] in [now]'s zone (today if it's still ahead, otherwise tomorrow). */
    fun delayUntilNext(now: ZonedDateTime, at: LocalTime = DAILY_AT): Duration {
        var next = now.with(at).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
