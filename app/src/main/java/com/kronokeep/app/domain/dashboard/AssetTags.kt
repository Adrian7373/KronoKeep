package com.kronokeep.app.domain.dashboard

import com.kronokeep.app.domain.maintenance.AssetSchedules
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.ScheduleWithPrediction
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What an asset card in the vault list shows besides the asset itself. */
sealed interface TagHighlight {
    /** The most urgent schedule; [othersNeedingAttention] counts the other overdue / due-soon ones. */
    data class Task(val item: ScheduleWithPrediction, val othersNeedingAttention: Int) : TagHighlight

    /** No dated task to show, but the warranty is ending soon or ended recently. */
    data class Warranty(val expiresOn: LocalDate, val daysLeft: Long) : TagHighlight

    /** Still under warranty, nothing ending soon. */
    data class UnderWarranty(val expiresOn: LocalDate) : TagHighlight
}

data class AssetTag(
    val asset: Asset,
    val highlight: TagHighlight?,
    /** Latest odometer for vehicles (asset field or newest reading, whichever is higher). */
    val currentKm: Int?,
)

object AssetTags {
    /**
     * One tag per asset, in the given order. A task that needs attention always wins; otherwise a warranty inside
     * the dashboard's window; otherwise the next OK task; otherwise an active warranty.
     */
    fun from(data: List<AssetSchedules>, today: LocalDate): List<AssetTag> = data.map { a ->
        val km = if (a.asset.category == AssetCategory.VEHICLE) {
            listOfNotNull(a.asset.currentOdometerKm, a.lastReading?.km).maxOrNull()
        } else {
            null
        }
        AssetTag(a.asset, highlight(a, today), km)
    }

    /** Archived assets: no reminders, so no task highlight. */
    fun plain(assets: List<Asset>): List<AssetTag> = assets.map {
        AssetTag(it, null, it.currentOdometerKm.takeIf { _ -> it.category == AssetCategory.VEHICLE })
    }

    private fun highlight(a: AssetSchedules, today: LocalDate): TagHighlight? {
        // Schedules arrive sorted by urgency (ObserveAllMaintenanceUseCase), but don't rely on it here.
        val sorted = a.schedules.sortedWith(
            compareBy<ScheduleWithPrediction>({ rank(it.prediction.status) }, { it.prediction.nextDueAt ?: java.time.Instant.MAX }),
        )
        val attention = sorted.filter { it.prediction.status != MaintenanceStatus.OK }
        if (attention.isNotEmpty()) return TagHighlight.Task(attention.first(), attention.size - 1)

        val warranty = a.asset.warrantyExpiresOn
        val daysLeft = warranty?.let { ChronoUnit.DAYS.between(today, it) }
        if (warranty != null && daysLeft != null &&
            daysLeft in -DashboardSummary.WARRANTY_EXPIRED_GRACE_DAYS..DashboardSummary.WARRANTY_AHEAD_DAYS
        ) {
            return TagHighlight.Warranty(warranty, daysLeft)
        }
        sorted.firstOrNull { it.prediction.nextDueAt != null || it.prediction.nextDueKm != null }
            ?.let { return TagHighlight.Task(it, 0) }
        if (warranty != null && daysLeft != null && daysLeft > 0) return TagHighlight.UnderWarranty(warranty)
        return null
    }

    private fun rank(s: MaintenanceStatus) = when (s) {
        MaintenanceStatus.OVERDUE -> 0
        MaintenanceStatus.DUE_SOON -> 1
        MaintenanceStatus.OK -> 2
    }
}
