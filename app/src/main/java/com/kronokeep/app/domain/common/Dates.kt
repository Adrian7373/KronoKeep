package com.kronokeep.app.domain.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object Dates {
    /**
     * The instant to store for a day the user picked. Today maps to `now`, so a same-day log sorts after
     * this morning's odometer reading. Past days map to local noon, which is safe from zone shifts.
     */
    fun instantFor(date: LocalDate, now: Instant, zone: ZoneId): Instant =
        if (date == now.atZone(zone).toLocalDate()) now else date.atTime(LocalTime.NOON).atZone(zone).toInstant()

    fun localDate(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()
}
