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
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class AssetTagsTest {
    private val now = Instant.parse("2026-11-14T00:00:00Z")
    private val today = LocalDate.of(2026, 11, 14)

    private fun asset(
        id: String,
        category: AssetCategory = AssetCategory.APPLIANCE,
        warranty: LocalDate? = null,
        odometer: Int? = null,
    ) = Asset(id, category, "Asset $id", null, null, null, null, null, warranty, odometer, null, null, null, now, SyncState.SYNCED)

    private fun item(id: String, status: MaintenanceStatus, dueInDays: Long?, dueKm: Int? = null) = ScheduleWithPrediction(
        MaintenanceSchedule(id, "x", "Task $id", 30, null, now, null, null, null, null, status, now, SyncState.SYNCED),
        DuePrediction(dueInDays?.let { now.plus(Duration.ofDays(it)) }, dueKm, status, 1.0, null, 0, UsageRate(null, RateSource.NONE, 0.0, 0), null),
    )

    private fun tag(a: AssetSchedules) = AssetTags.from(listOf(a), today).single()

    @Test fun `most urgent task wins and counts the others needing attention`() {
        val h = tag(
            AssetSchedules(
                asset("a", warranty = today.plusDays(5)),
                listOf(
                    item("ok", MaintenanceStatus.OK, 2),
                    item("soon", MaintenanceStatus.DUE_SOON, 3),
                    item("late", MaintenanceStatus.OVERDUE, -1),
                ),
                null,
            ),
        ).highlight as TagHighlight.Task
        assertEquals("late", h.item.schedule.id)
        assertEquals(1, h.othersNeedingAttention)
    }

    @Test fun `warranty inside the window beats an OK task`() {
        val h = tag(AssetSchedules(asset("a", warranty = today.plusDays(20)), listOf(item("ok", MaintenanceStatus.OK, 10)), null)).highlight
        assertEquals(TagHighlight.Warranty(today.plusDays(20), 20), h)
    }

    @Test fun `next OK task when nothing is urgent, undated tasks skipped`() {
        val h = tag(
            AssetSchedules(
                asset("a", warranty = today.plusDays(400)),
                listOf(item("undated", MaintenanceStatus.OK, null), item("later", MaintenanceStatus.OK, 50), item("next", MaintenanceStatus.OK, 30)),
                null,
            ),
        ).highlight as TagHighlight.Task
        assertEquals("next", h.item.schedule.id)
        assertEquals(0, h.othersNeedingAttention)
    }

    @Test fun `km-only task still counts as predictable`() {
        val h = tag(AssetSchedules(asset("a"), listOf(item("km", MaintenanceStatus.OK, null, dueKm = 8_000)), null)).highlight as TagHighlight.Task
        assertEquals("km", h.item.schedule.id)
    }

    @Test fun `electronic falls back to active warranty, then nothing`() {
        val under = tag(AssetSchedules(asset("e", AssetCategory.ELECTRONIC, warranty = today.plusDays(300)), emptyList(), null))
        assertEquals(TagHighlight.UnderWarranty(today.plusDays(300)), under.highlight)
        val old = tag(AssetSchedules(asset("o", AssetCategory.ELECTRONIC, warranty = today.minusDays(31)), emptyList(), null))
        assertNull(old.highlight)
    }

    @Test fun `vehicle km is the higher of the asset field and the newest reading`() {
        val reading = OdometerReading("r", "v", 9_100, now)
        assertEquals(9_100, tag(AssetSchedules(asset("v", AssetCategory.VEHICLE, odometer = 9_000), emptyList(), reading)).currentKm)
        assertEquals(9_000, tag(AssetSchedules(asset("v", AssetCategory.VEHICLE, odometer = 9_000), emptyList(), null)).currentKm)
        assertNull(tag(AssetSchedules(asset("f", odometer = 5), emptyList(), reading)).currentKm)
    }

    @Test fun `archived tags have no highlight`() {
        val tags = AssetTags.plain(listOf(asset("a", warranty = today.plusDays(3)), asset("v", AssetCategory.VEHICLE, odometer = 12)))
        assertEquals(listOf(null, null), tags.map { it.highlight })
        assertEquals(listOf(null, 12), tags.map { it.currentKm })
    }
}
