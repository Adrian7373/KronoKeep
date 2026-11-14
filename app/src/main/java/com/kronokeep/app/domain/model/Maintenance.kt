package com.kronokeep.app.domain.model

import com.kronokeep.app.domain.engine.DuePrediction
import com.kronokeep.app.domain.engine.ScheduleTerms
import java.time.Instant

/**
 * A recurring maintenance task. `nextDueAt` / `nextDueKm` / `confidence` / `status` are the engine output as
 * last persisted (for sync and the server); screens compute a live [DuePrediction] instead.
 */
data class MaintenanceSchedule(
    val id: String,
    val assetId: String,
    val task: String,
    val intervalDays: Int?,
    val intervalKm: Int?,
    val lastDoneAt: Instant?,
    val lastDoneKm: Int?,
    val nextDueAt: Instant?,
    val nextDueKm: Int?,
    val confidence: Double?,
    val status: MaintenanceStatus,
    val updatedAt: Instant,
    val syncState: SyncState,
) {
    val terms: ScheduleTerms get() = ScheduleTerms(intervalDays, intervalKm, lastDoneAt, lastDoneKm)

    fun toDraft() = ScheduleDraft(task, intervalDays, intervalKm, lastDoneAt, lastDoneKm)
}

/** User-editable fields of a schedule. */
data class ScheduleDraft(
    val task: String,
    val intervalDays: Int?,
    val intervalKm: Int?,
    val lastDoneAt: Instant?,
    val lastDoneKm: Int?,
) {
    fun normalized(category: AssetCategory): ScheduleDraft {
        val vehicle = category == AssetCategory.VEHICLE
        return copy(
            task = task.trim(),
            intervalKm = intervalKm.takeIf { vehicle },
            lastDoneKm = lastDoneKm.takeIf { vehicle && intervalKm != null },
        )
    }
}

data class ScheduleWithPrediction(val schedule: MaintenanceSchedule, val prediction: DuePrediction)

data class OdometerReading(val id: String, val assetId: String, val km: Int, val recordedAt: Instant)

data class ServiceLog(
    val id: String,
    val assetId: String,
    val scheduleId: String?,
    /** Task name of the linked schedule (kept even if the schedule was deleted later). Null for ad-hoc repairs. */
    val task: String?,
    val performedAt: Instant,
    val odometerKm: Int?,
    val cost: Peso?,
    val shop: String?,
    val notes: String?,
    val lockedAt: Instant?,
    val syncState: SyncState,
) {
    /** Included in a verified report: read-only in the app and in the database. */
    val isLocked: Boolean get() = lockedAt != null
}

/**
 * One trip to the shop. Writes one `service_logs` row per ticked schedule (sharing date and odometer, with the
 * cost on the first row), or a single unlinked row for an ad-hoc repair when nothing is ticked.
 */
data class ServiceVisit(
    val performedAt: Instant,
    val odometerKm: Int?,
    val cost: Peso?,
    val shop: String?,
    val notes: String?,
    val scheduleIds: List<String>,
) {
    fun normalized(category: AssetCategory): ServiceVisit = copy(
        odometerKm = odometerKm.takeIf { category == AssetCategory.VEHICLE },
        shop = shop?.trim()?.takeIf { it.isNotEmpty() },
        notes = notes?.trim()?.takeIf { it.isNotEmpty() },
        scheduleIds = scheduleIds.distinct(),
    )
}
