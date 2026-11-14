package com.kronokeep.app.domain.reminders

import com.kronokeep.app.domain.maintenance.AssetSchedules
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.MaintenanceStatus
import java.time.Duration
import java.time.Instant

/** What was last notified, so each reminder fires once per status change. Stored on the device only. */
data class ReminderLedger(
    /** scheduleId → status we last notified for (DUE_SOON or OVERDUE). */
    val scheduleStatus: Map<String, MaintenanceStatus> = emptyMap(),
    /** assetId → recordedAt of the reading that was stale when we nudged. */
    val staleOdometer: Map<String, Instant> = emptyMap(),
)

sealed interface Reminder {
    data class Due(
        val scheduleId: String,
        val assetId: String,
        val assetName: String,
        val task: String,
        val status: MaintenanceStatus,
    ) : Reminder

    data class UpdateOdometer(val assetId: String, val assetName: String, val daysSince: Long) : Reminder
}

data class ReminderPlan(val reminders: List<Reminder>, val ledger: ReminderLedger)

/**
 * Decides which notifications the daily job posts. Pure: the job supplies the predictions, the stored ledger and `now`.
 *
 * - A schedule is notified when it enters DUE_SOON or OVERDUE, and again only if it moves on to OVERDUE.
 *   Going back to OK (service logged) clears it, so the next cycle notifies again.
 * - A vehicle whose latest reading is older than [STALE_ODOMETER_NOTIFY_DAYS] gets one nudge per stale reading.
 */
object ReminderPlanner {
    const val STALE_ODOMETER_NOTIFY_DAYS = 14L

    fun plan(data: List<AssetSchedules>, ledger: ReminderLedger, now: Instant): ReminderPlan {
        val reminders = mutableListOf<Reminder>()
        val statuses = mutableMapOf<String, MaintenanceStatus>()
        val stale = mutableMapOf<String, Instant>()

        for (a in data) {
            for ((schedule, prediction) in a.schedules) {
                val status = prediction.status
                if (status == MaintenanceStatus.OK) continue
                statuses[schedule.id] = status
                if (ledger.scheduleStatus[schedule.id] != status) {
                    reminders += Reminder.Due(schedule.id, a.asset.id, a.asset.name, schedule.task, status)
                }
            }
            val last = a.lastReading
            if (a.asset.category == AssetCategory.VEHICLE && last != null && a.schedules.any { it.schedule.intervalKm != null }) {
                val days = Duration.between(last.recordedAt, now).toDays()
                if (days >= STALE_ODOMETER_NOTIFY_DAYS) {
                    stale[a.asset.id] = last.recordedAt
                    if (ledger.staleOdometer[a.asset.id] != last.recordedAt) {
                        reminders += Reminder.UpdateOdometer(a.asset.id, a.asset.name, days)
                    }
                }
            }
        }
        // Rebuilt from scratch: schedules that went back to OK, or were deleted, drop out of the ledger.
        return ReminderPlan(reminders, ReminderLedger(statuses, stale))
    }
}
