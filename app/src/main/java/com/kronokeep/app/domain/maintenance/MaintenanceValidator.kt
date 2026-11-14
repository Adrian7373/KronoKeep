package com.kronokeep.app.domain.maintenance

import com.kronokeep.app.domain.asset.AssetValidator
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.model.ServiceVisit
import java.time.Instant

enum class ScheduleField { TASK, INTERVAL_DAYS, INTERVAL_KM, LAST_DONE_AT, LAST_DONE_KM }

enum class VisitField { PERFORMED_AT, ODOMETER, COST, SHOP, NOTES }

sealed interface MaintenanceError {
    data object Required : MaintenanceError
    data class TooLong(val max: Int) : MaintenanceError
    data object Negative : MaintenanceError
    data object NotPositive : MaintenanceError
    data class TooLarge(val max: Int) : MaintenanceError
    data object InFuture : MaintenanceError
    /** A schedule needs a day interval, a km interval, or both. */
    data object NoInterval : MaintenanceError
    /** Km is above the asset's current odometer. */
    data class AboveOdometer(val currentKm: Int) : MaintenanceError
    /** A new odometer reading can't go backwards. */
    data class BelowLastReading(val lastKm: Int) : MaintenanceError
    /** A km-based task was ticked but no odometer was entered. */
    data object OdometerNeeded : MaintenanceError
    /** Nothing ticked and no description of the repair. */
    data object NothingLogged : MaintenanceError
    /** Raised by input parsing, before validation. */
    data object InvalidNumber : MaintenanceError
}

/** Same limits as the SQL CHECK constraints. `now` is a parameter so the rules stay deterministic. */
object MaintenanceValidator {
    const val TASK_MAX = 120
    const val SHOP_MAX = 120
    const val NOTES_MAX = AssetValidator.NOTES_MAX
    const val INTERVAL_DAYS_MAX = 3_650
    const val INTERVAL_KM_MAX = 200_000
    const val ODOMETER_MAX = AssetValidator.ODOMETER_MAX

    fun validateSchedule(
        draft: ScheduleDraft,
        category: AssetCategory,
        currentKm: Int?,
        now: Instant,
    ): Map<ScheduleField, MaintenanceError> = buildMap {
        when {
            draft.task.isBlank() -> put(ScheduleField.TASK, MaintenanceError.Required)
            draft.task.length > TASK_MAX -> put(ScheduleField.TASK, MaintenanceError.TooLong(TASK_MAX))
        }
        val km = draft.intervalKm.takeIf { category == AssetCategory.VEHICLE }
        if (draft.intervalDays == null && km == null) {
            put(ScheduleField.INTERVAL_DAYS, MaintenanceError.NoInterval)
            if (category == AssetCategory.VEHICLE) put(ScheduleField.INTERVAL_KM, MaintenanceError.NoInterval)
        }
        draft.intervalDays?.let {
            when {
                it <= 0 -> put(ScheduleField.INTERVAL_DAYS, MaintenanceError.NotPositive)
                it > INTERVAL_DAYS_MAX -> put(ScheduleField.INTERVAL_DAYS, MaintenanceError.TooLarge(INTERVAL_DAYS_MAX))
            }
        }
        km?.let {
            when {
                it <= 0 -> put(ScheduleField.INTERVAL_KM, MaintenanceError.NotPositive)
                it > INTERVAL_KM_MAX -> put(ScheduleField.INTERVAL_KM, MaintenanceError.TooLarge(INTERVAL_KM_MAX))
            }
        }
        draft.lastDoneAt?.let { if (it.isAfter(now)) put(ScheduleField.LAST_DONE_AT, MaintenanceError.InFuture) }
        if (km != null) {
            when (val done = draft.lastDoneKm) {
                null -> put(ScheduleField.LAST_DONE_KM, MaintenanceError.Required)
                else -> when {
                    done < 0 -> put(ScheduleField.LAST_DONE_KM, MaintenanceError.Negative)
                    currentKm != null && done > currentKm -> put(ScheduleField.LAST_DONE_KM, MaintenanceError.AboveOdometer(currentKm))
                }
            }
        }
    }

    /** [kmScheduleIds] are the ticked schedules that have a km interval. */
    fun validateVisit(
        visit: ServiceVisit,
        category: AssetCategory,
        kmScheduleIds: Set<String>,
        now: Instant,
    ): Map<VisitField, MaintenanceError> = buildMap {
        if (visit.performedAt.isAfter(now)) put(VisitField.PERFORMED_AT, MaintenanceError.InFuture)
        if (visit.scheduleIds.isEmpty() && visit.notes.isNullOrBlank()) put(VisitField.NOTES, MaintenanceError.NothingLogged)
        visit.notes?.let { if (it.length > NOTES_MAX) put(VisitField.NOTES, MaintenanceError.TooLong(NOTES_MAX)) }
        visit.shop?.let { if (it.length > SHOP_MAX) put(VisitField.SHOP, MaintenanceError.TooLong(SHOP_MAX)) }
        visit.cost?.let { if (it.centavos < 0) put(VisitField.COST, MaintenanceError.Negative) }
        if (category == AssetCategory.VEHICLE) {
            when (val km = visit.odometerKm) {
                null -> if (visit.scheduleIds.any { it in kmScheduleIds }) put(VisitField.ODOMETER, MaintenanceError.OdometerNeeded)
                else -> when {
                    km < 0 -> put(VisitField.ODOMETER, MaintenanceError.Negative)
                    km > ODOMETER_MAX -> put(VisitField.ODOMETER, MaintenanceError.TooLarge(ODOMETER_MAX))
                }
            }
        }
    }

    fun validateOdometer(km: Int, lastKm: Int?): MaintenanceError? = when {
        km < 0 -> MaintenanceError.Negative
        km > ODOMETER_MAX -> MaintenanceError.TooLarge(ODOMETER_MAX)
        lastKm != null && km < lastKm -> MaintenanceError.BelowLastReading(lastKm)
        else -> null
    }
}
