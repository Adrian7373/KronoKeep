package com.kronokeep.app.domain.engine

import com.kronokeep.app.domain.model.MaintenanceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MaintenanceEngineTest {
    private val now = Instant.parse("2026-11-14T00:00:00Z")
    private fun daysAgo(d: Long): Instant = now.minus(Duration.ofDays(d))
    private fun reading(km: Int, daysAgo: Long) = OdometerPoint(km, daysAgo(daysAgo))

    @Test fun `fewer than two readings falls back to weekly baseline`() {
        val rate = MaintenanceEngine.usageRate(UsageInput(listOf(reading(1000, 3)), 1000, 70), now)
        assertEquals(RateSource.BASELINE, rate.source)
        assertEquals(10.0, rate.kmPerDay!!, 1e-9)
        assertEquals(MaintenanceEngine.BASELINE_CONFIDENCE, rate.confidence, 1e-9)
    }

    @Test fun `no readings and no baseline gives no rate`() {
        val rate = MaintenanceEngine.usageRate(UsageInput(emptyList(), null, null), now)
        assertEquals(RateSource.NONE, rate.source)
        assertNull(rate.kmPerDay)
        assertEquals(0.0, rate.confidence, 0.0)
    }

    @Test fun `zero baseline counts as no rate`() {
        assertEquals(RateSource.NONE, MaintenanceEngine.usageRate(UsageInput(emptyList(), 500, 0), now).source)
    }

    @Test fun `recent intervals outweigh old ones`() {
        // 10 km/day long ago, 40 km/day recently, equal lengths: the estimate leans to 40.
        val readings = listOf(reading(0, 120), reading(300, 90), reading(1500, 60), reading(2700, 30))
        val rate = MaintenanceEngine.usageRate(UsageInput(readings, 2700, null), now)
        assertEquals(RateSource.EWMA, rate.source)
        assertTrue("rate ${rate.kmPerDay} should lean recent", rate.kmPerDay!! > 30.0)
    }

    @Test fun `long intervals outweigh short ones of the same age`() {
        // A 2-day sprint at 100 km/day vs a 20-day stretch at 20 km/day, both centred about the same time.
        val readings = listOf(reading(0, 31), reading(400, 11), reading(600, 9))
        val rate = MaintenanceEngine.usageRate(UsageInput(readings, 600, null), now)
        assertTrue("rate ${rate.kmPerDay} should stay near 20", rate.kmPerDay!! < 30.0)
    }

    @Test fun `stale readings lower confidence`() {
        val fresh = MaintenanceEngine.usageRate(UsageInput(listOf(reading(0, 20), reading(200, 10), reading(400, 0)), 400, null), now)
        val stale = MaintenanceEngine.usageRate(
            UsageInput(listOf(reading(0, 80), reading(200, 70), reading(400, 60)), 400, null),
            now,
        )
        assertEquals(fresh.kmPerDay!!, stale.kmPerDay!!, 1e-9)
        assertTrue(stale.confidence < fresh.confidence / 3)
    }

    @Test fun `erratic usage lowers confidence`() {
        val steady = MaintenanceEngine.usageRate(UsageInput(listOf(reading(0, 20), reading(200, 10), reading(400, 0)), 400, null), now)
        val erratic = MaintenanceEngine.usageRate(UsageInput(listOf(reading(0, 20), reading(20, 10), reading(400, 0)), 400, null), now)
        assertTrue(erratic.confidence < steady.confidence)
    }

    @Test fun `more readings raise confidence`() {
        val two = MaintenanceEngine.usageRate(UsageInput(listOf(reading(0, 10), reading(200, 0)), 200, null), now)
        val four = MaintenanceEngine.usageRate(
            UsageInput(listOf(reading(0, 30), reading(200, 20), reading(400, 10), reading(600, 0)), 600, null),
            now,
        )
        assertTrue(four.confidence > two.confidence)
    }

    @Test fun `readings out of order are sorted first`() {
        val ordered = MaintenanceEngine.usageRate(UsageInput(listOf(reading(0, 20), reading(200, 10), reading(500, 0)), 500, null), now)
        val shuffled = MaintenanceEngine.usageRate(UsageInput(listOf(reading(500, 0), reading(0, 20), reading(200, 10)), 500, null), now)
        assertEquals(ordered, shuffled)
    }

    @Test fun `calendar trigger wins when it comes first`() {
        val p = MaintenanceEngine.evaluate(
            ScheduleTerms(intervalDays = 30, intervalKm = 5000, lastDoneAt = daysAgo(0), lastDoneKm = 1000),
            UsageInput(listOf(reading(1000, 0)), 1000, 70),
            now,
        )
        assertEquals(DueTrigger.CALENDAR, p.trigger)
        assertEquals(now.plus(Duration.ofDays(30)), p.nextDueAt)
        assertEquals(1.0, p.confidence, 0.0)
        assertEquals(0, p.uncertaintyDays)
        assertEquals(6000, p.nextDueKm)
    }

    @Test fun `10 percent of the calendar interval left is due soon even beyond 14 days`() {
        // 365-day interval with 20 days left: outside 14 days but inside 36.5.
        val p = MaintenanceEngine.evaluate(
            ScheduleTerms(intervalDays = 365, intervalKm = null, lastDoneAt = daysAgo(345), lastDoneKm = null),
            UsageInput(emptyList(), null, null),
            now,
        )
        assertEquals(MaintenanceStatus.DUE_SOON, p.status)
    }

    @Test fun `exactly at the due instant is overdue`() {
        val p = MaintenanceEngine.evaluate(
            ScheduleTerms(intervalDays = 10, intervalKm = null, lastDoneAt = daysAgo(10), lastDoneKm = null),
            UsageInput(emptyList(), null, null),
            now,
        )
        assertEquals(MaintenanceStatus.OVERDUE, p.status)
    }

    @Test fun `missing last done disables a trigger`() {
        val p = MaintenanceEngine.evaluate(
            ScheduleTerms(intervalDays = 10, intervalKm = 1000, lastDoneAt = null, lastDoneKm = null),
            UsageInput(listOf(reading(500, 0)), 500, 70),
            now,
        )
        assertNull(p.trigger)
        assertNull(p.nextDueAt)
        assertNull(p.nextDueKm)
        assertEquals(MaintenanceStatus.OK, p.status)
    }

    @Test fun `asset odometer ahead of readings anchors at now`() {
        val anchor = MaintenanceEngine.anchor(UsageInput(listOf(reading(1000, 5)), 1200, null), now)
        assertEquals(OdometerPoint(1200, now), anchor)
    }

    @Test fun `projection is stable over time without new readings`() {
        val terms = ScheduleTerms(intervalDays = null, intervalKm = 1500, lastDoneAt = daysAgo(10), lastDoneKm = 10000)
        val usage = UsageInput(listOf(reading(10000, 10)), 10000, 140)
        val today = MaintenanceEngine.evaluate(terms, usage, now)
        val nextWeek = MaintenanceEngine.evaluate(terms, usage, now.plus(Duration.ofDays(7)))
        assertEquals(today.nextDueAt, nextWeek.nextDueAt)
        assertEquals(today.projectedKm!! + 140, nextWeek.projectedKm)
    }

    @Test fun `engine is deterministic`() {
        val terms = ScheduleTerms(60, 1500, daysAgo(20), 9000)
        val usage = UsageInput(listOf(reading(9000, 20), reading(9300, 10), reading(9650, 1)), 9650, 100)
        assertEquals(MaintenanceEngine.evaluate(terms, usage, now), MaintenanceEngine.evaluate(terms, usage, now))
    }
}
