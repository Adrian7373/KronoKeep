package com.kronokeep.app.domain.dashboard

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class DashboardSummaryTest {
    private val now = Instant.parse("2026-11-14T00:00:00Z")
    private val today = LocalDate.of(2026, 11, 14)

    private fun asset(id: String, category: AssetCategory = AssetCategory.APPLIANCE, warranty: LocalDate? = null) = Asset(
        id, category, "Asset $id", null, null, null, null, null, warranty, null, null, null, null, now, SyncState.SYNCED,
    )

    private fun item(id: String, status: MaintenanceStatus, dueInDays: Long?) = ScheduleWithPrediction(
        MaintenanceSchedule(id, "x", "Task $id", 30, null, now, null, null, null, null, status, now, SyncState.SYNCED),
        DuePrediction(dueInDays?.let { now.plus(Duration.ofDays(it)) }, null, status, 1.0, null, 0, UsageRate(null, RateSource.NONE, 0.0, 0), null),
    )

    @Test fun `groups by status, soonest first`() {
        val data = listOf(
            AssetSchedules(
                asset("a"),
                listOf(
                    item("late", MaintenanceStatus.OVERDUE, -3),
                    item("later", MaintenanceStatus.OVERDUE, -10),
                    item("soon", MaintenanceStatus.DUE_SOON, 5),
                    item("ok1", MaintenanceStatus.OK, 40),
                    item("ok2", MaintenanceStatus.OK, 20),
                    item("undated", MaintenanceStatus.OK, null),
                ),
                null,
            ),
        )
        val s = DashboardSummary.from(data, today, now)
        assertEquals(listOf("later", "late"), s.overdue.map { it.item.schedule.id })
        assertEquals(listOf("soon"), s.dueSoon.map { it.item.schedule.id })
        assertEquals(listOf("ok2", "ok1"), s.upcoming.map { it.item.schedule.id })
        assertFalse(s.isAllClear)
        assertEquals(6, s.scheduleCount)
    }

    @Test fun `warranties within 60 days ahead or 30 days expired`() {
        val data = listOf(
            AssetSchedules(asset("soon", warranty = today.plusDays(45)), emptyList(), null),
            AssetSchedules(asset("far", warranty = today.plusDays(61)), emptyList(), null),
            AssetSchedules(asset("lapsed", warranty = today.minusDays(10)), emptyList(), null),
            AssetSchedules(asset("old", warranty = today.minusDays(31)), emptyList(), null),
        )
        val s = DashboardSummary.from(data, today, now)
        assertEquals(listOf("lapsed", "soon"), s.warranties.map { it.asset.id })
        assertEquals(-10L, s.warranties.first().daysLeft)
        assertTrue(s.isAllClear)
    }

    @Test fun `featured asset is filled in, and null without assets`() {
        val data = listOf(AssetSchedules(asset("a"), emptyList(), null))
        assertEquals("a", DashboardSummary.from(data, today, now).featured?.asset?.id)
        assertNull(DashboardSummary.from(emptyList(), today, now).featured)
    }

    @Test fun `vehicles with readings older than a week or none are stale`() {
        fun reading(daysAgo: Long) = OdometerReading("r", "v", 1_000, now.minus(Duration.ofDays(daysAgo)))
        val data = listOf(
            AssetSchedules(asset("fresh", AssetCategory.VEHICLE), emptyList(), reading(7)),
            AssetSchedules(asset("stale", AssetCategory.VEHICLE), emptyList(), reading(8)),
            AssetSchedules(asset("never", AssetCategory.VEHICLE), emptyList(), null),
            AssetSchedules(asset("fridge"), emptyList(), null),
        )
        assertEquals(listOf("stale", "never"), DashboardSummary.from(data, today, now).staleOdometers.map { it.asset.id })
    }
}
