package com.kronokeep.app.domain.dashboard

import com.kronokeep.app.domain.engine.UsageRate
import com.kronokeep.app.domain.maintenance.AssetSchedules
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.ScheduleWithPrediction
import java.time.Duration
import java.time.Instant

/** The asset the user works with most, shown in the dashboard hero. */
data class FeaturedAsset(
    val asset: Asset,
    /** Vehicles only: asset field or newest reading, whichever is higher. */
    val currentKm: Int?,
    /** Vehicles only. */
    val rate: UsageRate?,
    val lastReadingAt: Instant?,
    /** Most recent "last done" across the asset's schedules. */
    val lastServiceAt: Instant?,
    /** Most urgent schedule with a due point, if any. */
    val nextTask: ScheduleWithPrediction?,
    /** Overdue plus due-soon schedules. */
    val attentionCount: Int,
)

object FeaturedAssets {
    const val ACTIVITY_WINDOW_DAYS = 90L

    /**
     * Picks the asset with the most activity in the last [ACTIVITY_WINDOW_DAYS]: odometer readings, schedules
     * marked done, and edits to the asset itself. Ties go to the most recent activity, then the name.
     * Schedule "last done" stands in for service logs, which the dashboard flow doesn't carry.
     */
    fun pick(data: List<AssetSchedules>, now: Instant): FeaturedAsset? {
        if (data.isEmpty()) return null
        val since = now.minus(Duration.ofDays(ACTIVITY_WINDOW_DAYS))
        val best = data.maxWithOrNull(
            compareBy<AssetSchedules>({ activityCount(it, since) }, { lastActivity(it) })
                .thenByDescending { it.asset.name },
        ) ?: return null
        return featured(best)
    }

    private fun events(a: AssetSchedules): List<Instant> =
        a.readings.ifEmpty { listOfNotNull(a.lastReading) }.map { it.recordedAt } +
            a.schedules.mapNotNull { it.schedule.lastDoneAt } +
            a.asset.updatedAt

    private fun activityCount(a: AssetSchedules, since: Instant) = events(a).count { it >= since }

    private fun lastActivity(a: AssetSchedules): Instant = events(a).max()

    private fun featured(a: AssetSchedules): FeaturedAsset {
        val vehicle = a.asset.category == AssetCategory.VEHICLE
        val latest = a.readings.maxByOrNull { it.recordedAt } ?: a.lastReading
        val ranked = a.schedules.sortedWith(
            compareBy<ScheduleWithPrediction>({ rank(it.prediction.status) }, { it.prediction.nextDueAt ?: Instant.MAX }),
        )
        return FeaturedAsset(
            asset = a.asset,
            currentKm = if (vehicle) listOfNotNull(a.asset.currentOdometerKm, latest?.km).maxOrNull() else null,
            rate = if (vehicle) a.rate else null,
            lastReadingAt = if (vehicle) latest?.recordedAt else null,
            lastServiceAt = a.schedules.mapNotNull { it.schedule.lastDoneAt }.maxOrNull(),
            nextTask = ranked.firstOrNull { it.prediction.nextDueAt != null || it.prediction.nextDueKm != null },
            attentionCount = a.schedules.count { it.prediction.status != MaintenanceStatus.OK },
        )
    }

    private fun rank(s: MaintenanceStatus) = when (s) {
        MaintenanceStatus.OVERDUE -> 0
        MaintenanceStatus.DUE_SOON -> 1
        MaintenanceStatus.OK -> 2
    }
}
