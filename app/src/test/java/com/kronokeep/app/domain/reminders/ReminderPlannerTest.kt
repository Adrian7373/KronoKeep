package com.kronokeep.app.domain.reminders

import com.kronokeep.app.domain.engine.DuePrediction
import com.kronokeep.app.domain.engine.RateSource
import com.kronokeep.app.domain.engine.UsageRate
import com.kronokeep.app.domain.maintenance.AssetSchedules
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.MaintenanceSchedule
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.OdometerReading
import com.kronokeep.app.domain.model.ScheduleWithPrediction
import com.kronokeep.app.domain.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderPlannerTest {
    private val now = Instant.parse("2026-11-14T00:00:00Z")
    private val asset = Asset(
        "click", AssetCategory.VEHICLE, "Click 125i", null, null, null, null, null, null, 10_000, 140, null, null, now, SyncState.SYNCED,
    )

    private fun item(id: String, status: MaintenanceStatus, km: Int? = 1_500) = ScheduleWithPrediction(
        MaintenanceSchedule(id, asset.id, "Task $id", 60, km, now, 10_000, null, null, null, status, now, SyncState.SYNCED),
        DuePrediction(now, null, status, 1.0, null, 0, UsageRate(null, RateSource.NONE, 0.0, 0), null),
    )

    private fun data(vararg items: ScheduleWithPrediction, readingDaysAgo: Long = 1) = listOf(
        AssetSchedules(asset, items.toList(), OdometerReading("r", asset.id, 10_000, now.minus(Duration.ofDays(readingDaysAgo)))),
    )

    @Test fun `notifies once when a task becomes due soon`() {
        val first = ReminderPlanner.plan(data(item("a", MaintenanceStatus.DUE_SOON)), ReminderLedger(), now)
        assertEquals(listOf("a"), first.reminders.filterIsInstance<Reminder.Due>().map { it.scheduleId })

        val second = ReminderPlanner.plan(data(item("a", MaintenanceStatus.DUE_SOON)), first.ledger, now)
        assertTrue(second.reminders.isEmpty())
    }

    @Test fun `notifies again when due soon turns overdue`() {
        val ledger = ReminderLedger(mapOf("a" to MaintenanceStatus.DUE_SOON))
        val plan = ReminderPlanner.plan(data(item("a", MaintenanceStatus.OVERDUE)), ledger, now)
        assertEquals(MaintenanceStatus.OVERDUE, (plan.reminders.single() as Reminder.Due).status)
    }

    @Test fun `ok tasks are silent and drop out of the ledger`() {
        val ledger = ReminderLedger(mapOf("a" to MaintenanceStatus.OVERDUE))
        val plan = ReminderPlanner.plan(data(item("a", MaintenanceStatus.OK)), ledger, now)
        assertTrue(plan.reminders.isEmpty())
        assertTrue(plan.ledger.scheduleStatus.isEmpty())

        // So the next cycle notifies again.
        val next = ReminderPlanner.plan(data(item("a", MaintenanceStatus.DUE_SOON)), plan.ledger, now)
        assertEquals(1, next.reminders.size)
    }

    @Test fun `stale odometer nudges once per reading`() {
        val stale = data(item("a", MaintenanceStatus.OK), readingDaysAgo = 15)
        val first = ReminderPlanner.plan(stale, ReminderLedger(), now)
        assertEquals(15L, (first.reminders.single() as Reminder.UpdateOdometer).daysSince)
        assertTrue(ReminderPlanner.plan(stale, first.ledger, now).reminders.isEmpty())
    }

    @Test fun `no odometer nudge before 14 days or without km tasks`() {
        assertTrue(ReminderPlanner.plan(data(item("a", MaintenanceStatus.OK), readingDaysAgo = 13), ReminderLedger(), now).reminders.isEmpty())
        assertTrue(
            ReminderPlanner.plan(data(item("a", MaintenanceStatus.OK, km = null), readingDaysAgo = 40), ReminderLedger(), now).reminders.isEmpty(),
        )
    }

    @Test fun `daily delay targets the next 8am local`() {
        val manila = ZoneId.of("Asia/Manila")
        val early = ZonedDateTime.of(2026, 11, 14, 6, 30, 0, 0, manila)
        assertEquals(Duration.ofMinutes(90), ReminderTiming.delayUntilNext(early))
        val late = ZonedDateTime.of(2026, 11, 14, 8, 0, 0, 0, manila)
        assertEquals(Duration.ofHours(24), ReminderTiming.delayUntilNext(late))
        assertEquals(LocalTime.of(8, 0), ReminderTiming.DAILY_AT)
    }
}
