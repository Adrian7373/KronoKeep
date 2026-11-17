package com.kronokeep.app.ui.maintenance

import com.kronokeep.app.domain.engine.DuePrediction
import com.kronokeep.app.domain.engine.RateSource
import com.kronokeep.app.domain.engine.UsageRate
import com.kronokeep.app.domain.maintenance.DueWindow
import com.kronokeep.app.domain.model.MaintenanceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class MaintenanceFormatsTest {
    private val manila = ZoneId.of("Asia/Manila")
    private val now = Instant.parse("2026-11-16T02:00:00Z")
    private val day = Duration.ofDays(1)

    private fun p(dueInDays: Long?, u: Int?, status: MaintenanceStatus = MaintenanceStatus.OK, km: Int? = null) =
        DuePrediction(dueInDays?.let { now + day.multipliedBy(it) }, km, status, 0.5, null, u, UsageRate(null, RateSource.NONE, 0.0, 0), null)

    @Test fun `tag label covers overdue, ranges, exact dates, today and km-only`() {
        assertEquals("Oil overdue", MaintenanceFormats.tagLabel("Oil", p(-3, 2, MaintenanceStatus.OVERDUE), now))
        assertEquals("PMS in 9–13 d", MaintenanceFormats.tagLabel("PMS", p(11, 2), now))
        assertEquals("PMS in 0–5 d", MaintenanceFormats.tagLabel("PMS", p(2, 3), now))
        assertEquals("Clean in 12 d", MaintenanceFormats.tagLabel("Clean", p(12, 0), now))
        assertEquals("Clean today", MaintenanceFormats.tagLabel("Clean", p(0, null), now))
        assertEquals("Oil at 8,000 km", MaintenanceFormats.tagLabel("Oil", p(null, null, km = 8_000), now))
        assertNull(MaintenanceFormats.tagLabel("Oil", p(null, null), now))
    }

    @Test fun `window text names the most likely day and the range, or the exact date`() {
        val wide = DueWindow.of(p(14, 7), now)!!
        assertEquals("Most likely 30 Nov. Window 23 Nov to 7 Dec.", MaintenanceFormats.window(wide, manila))
        val exact = DueWindow.of(p(14, 0), now)!!
        assertEquals("Due 30 Nov 2026.", MaintenanceFormats.window(exact, manila))
    }

    @Test fun `ago reads today, yesterday or days`() {
        assertEquals("Today", MaintenanceFormats.ago(now.minus(Duration.ofHours(5)), now))
        assertEquals("Today", MaintenanceFormats.ago(now.plus(day), now))
        assertEquals("Yesterday", MaintenanceFormats.ago(now - day, now))
        assertEquals("12 d ago", MaintenanceFormats.ago(now - day.multipliedBy(12), now))
    }

    @Test fun `axis labels use months on long tracks and days on short ones`() {
        val long = MaintenanceFormats.axisLabels(DueWindow.of(p(120, 10), now)!!, manila)
        assertEquals(3, long.size)
        assertEquals(true, long.all { it.length == 3 })
        val short = MaintenanceFormats.axisLabels(DueWindow.of(p(5, 0), now)!!, manila)
        assertEquals(true, short.all { ' ' in it })
    }
}
