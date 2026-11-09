package com.kronokeep.app.domain.dashboard

import com.kronokeep.app.domain.maintenance.AssetSchedules
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.ScheduleWithPrediction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class DueItem(val asset: Asset, val item: ScheduleWithPrediction)

data class WarrantyItem(val asset: Asset, val expiresOn: LocalDate, val daysLeft: Long)

/** A vehicle whose odometer hasn't been updated recently (or ever), so km-based predictions are drifting. */
data class StaleOdometer(val asset: Asset, val lastReadingAt: Instant?, val daysSince: Long?)

data class DashboardSummary(
    val overdue: List<DueItem>,
    val dueSoon: List<DueItem>,
    /** The next few OK items, so the screen isn't empty when nothing is urgent. */
    val upcoming: List<DueItem>,
    val warranties: List<WarrantyItem>,
    val staleOdometers: List<StaleOdometer>,
    val assetCount: Int,
    val scheduleCount: Int,
) {
    val isAllClear: Boolean get() = overdue.isEmpty() && dueSoon.isEmpty()

    companion object {
        const val WARRANTY_AHEAD_DAYS = 60L
        /** Recently expired warranties stay visible for a while: that's when claims get missed. */
        const val WARRANTY_EXPIRED_GRACE_DAYS = 30L
        const val STALE_ODOMETER_DAYS = 7L
        const val UPCOMING_LIMIT = 3

        fun from(data: List<AssetSchedules>, today: LocalDate, now: Instant): DashboardSummary {
            val items = data.flatMap { a -> a.schedules.map { DueItem(a.asset, it) } }
            fun byStatus(status: MaintenanceStatus) = items
                .filter { it.item.prediction.status == status }
                .sortedBy { it.item.prediction.nextDueAt ?: Instant.MAX }

            val warranties = data.mapNotNull { a ->
                val expires = a.asset.warrantyExpiresOn ?: return@mapNotNull null
                val days = ChronoUnit.DAYS.between(today, expires)
                if (days in -WARRANTY_EXPIRED_GRACE_DAYS..WARRANTY_AHEAD_DAYS) WarrantyItem(a.asset, expires, days) else null
            }.sortedBy { it.expiresOn }

            val stale = data
                .filter { it.asset.category == AssetCategory.VEHICLE }
                .mapNotNull { a ->
                    val at = a.lastReading?.recordedAt
                    val days = at?.let { Duration.between(it, now).toDays() }
                    if (days == null || days > STALE_ODOMETER_DAYS) StaleOdometer(a.asset, at, days) else null
                }

            return DashboardSummary(
                overdue = byStatus(MaintenanceStatus.OVERDUE),
                dueSoon = byStatus(MaintenanceStatus.DUE_SOON),
                upcoming = byStatus(MaintenanceStatus.OK).filter { it.item.prediction.nextDueAt != null }.take(UPCOMING_LIMIT),
                warranties = warranties,
                staleOdometers = stale,
                assetCount = data.size,
                scheduleCount = items.size,
            )
        }
    }
}
