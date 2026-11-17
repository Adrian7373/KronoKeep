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

class FeaturedAssetsTest {
    private val now = Instant.parse("2026-11-14T00:00:00Z")
    private fun daysAgo(d: Long) = now.minus(Duration.ofDays(d))
    private val rate = UsageRate(20.0, RateSource.EWMA, 0.8, 3)

    private fun asset(
        id: String,
        category: AssetCategory = AssetCategory.APPLIANCE,
        updated: Instant = daysAgo(200),
        odometer: Int? = null,
    ) = Asset(id, category, "Asset $id", null, null, null, null, null, null, odometer, null, null, null, updated, SyncState.SYNCED)

    private fun readings(assetId: String, vararg days: Long) =
        days.mapIndexed { i, d -> OdometerReading("$assetId-r$i", assetId, 1_000 + i * 100, daysAgo(d)) }

    private fun item(id: String, status: MaintenanceStatus, dueInDays: Long?, lastDone: Instant? = null) = ScheduleWithPrediction(
        MaintenanceSchedule(id, "x", "Task $id", 30, null, lastDone, null, null, null, null, status, now, SyncState.SYNCED),
        DuePrediction(dueInDays?.let { now.plus(Duration.ofDays(it)) }, null, status, 1.0, null, 0, rate, null),
    )

    private fun data(asset: Asset, schedules: List<ScheduleWithPrediction> = emptyList(), readings: List<OdometerReading> = emptyList()) =
        AssetSchedules(asset, schedules, readings.maxByOrNull { it.recordedAt }, readings, rate)

    @Test fun `no assets, nothing featured`() {
        assertNull(FeaturedAssets.pick(emptyList(), now))
    }

    @Test fun `most activity in the window wins`() {
        val picked = FeaturedAssets.pick(
            listOf(
                data(asset("fridge"), listOf(item("clean", MaintenanceStatus.OK, 20, lastDone = daysAgo(3)))),
                data(asset("car", AssetCategory.VEHICLE), readings = readings("car", 60, 30, 10)),
            ),
            now,
        )
        assertEquals("car", picked?.asset?.id)
    }

    @Test fun `activity older than the window does not count`() {
        val picked = FeaturedAssets.pick(
            listOf(
                data(asset("old", AssetCategory.VEHICLE), readings = readings("old", 300, 250, 200, 150)),
                data(asset("recent"), listOf(item("s", MaintenanceStatus.OK, 20, lastDone = daysAgo(5)))),
            ),
            now,
        )
        assertEquals("recent", picked?.asset?.id)
    }

    @Test fun `ties go to the most recent activity`() {
        val picked = FeaturedAssets.pick(
            listOf(
                data(asset("a", updated = daysAgo(20))),
                data(asset("b", updated = daysAgo(2))),
            ),
            now,
        )
        assertEquals("b", picked?.asset?.id)
    }

    @Test fun `vehicle details come from the newest reading and the most urgent task`() {
        val f = FeaturedAssets.pick(
            listOf(
                data(
                    asset("car", AssetCategory.VEHICLE, odometer = 900),
                    listOf(
                        item("ok", MaintenanceStatus.OK, 40, lastDone = daysAgo(50)),
                        item("late", MaintenanceStatus.OVERDUE, -2, lastDone = daysAgo(80)),
                        item("soon", MaintenanceStatus.DUE_SOON, 5),
                    ),
                    readings("car", 20, 4),
                ),
            ),
            now,
        )!!
        assertEquals(1_100, f.currentKm)
        assertEquals(rate, f.rate)
        assertEquals(daysAgo(4), f.lastReadingAt)
        assertEquals(daysAgo(50), f.lastServiceAt)
        assertEquals("late", f.nextTask?.schedule?.id)
        assertEquals(2, f.attentionCount)
    }

    @Test fun `non-vehicles carry no odometer details`() {
        val f = FeaturedAssets.pick(listOf(data(asset("tv", AssetCategory.ELECTRONIC, odometer = 5))), now)!!
        assertNull(f.currentKm)
        assertNull(f.rate)
        assertNull(f.lastReadingAt)
        assertNull(f.nextTask)
    }
}
