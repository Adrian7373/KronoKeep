package com.kronokeep.app.domain.engine

import com.kronokeep.app.domain.model.MaintenanceStatus
import java.time.Instant

/** One odometer reading as the engine sees it. */
data class OdometerPoint(val km: Int, val at: Instant)

/** What the engine needs from a schedule. A trigger only counts when its interval and its "last done" value are both set. */
data class ScheduleTerms(
    val intervalDays: Int?,
    val intervalKm: Int?,
    val lastDoneAt: Instant?,
    val lastDoneKm: Int?,
)

/** Usage history of one asset. */
data class UsageInput(
    val readings: List<OdometerPoint>,
    val currentOdometerKm: Int?,
    val weeklyKmBaseline: Int?,
)

enum class RateSource(val wire: String) { EWMA("ewma"), BASELINE("baseline"), NONE("none") }

/** Estimated km/day and how much to trust it (0..1). [kmPerDay] is null when [source] is NONE. */
data class UsageRate(
    val kmPerDay: Double?,
    val source: RateSource,
    val confidence: Double,
    /** Number of reading intervals the EWMA used (0 for baseline / none). */
    val intervals: Int,
)

enum class DueTrigger(val wire: String) { CALENDAR("calendar"), USAGE("usage") }

/**
 * Engine output for one schedule.
 *
 * [nextDueAt] is the earlier of the calendar and usage due points (null when neither can be dated, e.g. a
 * km-only schedule with no usage rate). [uncertaintyDays] is the ± half-width of the due window; the UI shows
 * it instead of pretending to know the exact day.
 */
data class DuePrediction(
    val nextDueAt: Instant?,
    val nextDueKm: Int?,
    val status: MaintenanceStatus,
    val confidence: Double,
    val trigger: DueTrigger?,
    val uncertaintyDays: Int?,
    val rate: UsageRate,
    /** Best guess of the odometer right now (last reading projected forward at [UsageRate.kmPerDay]). */
    val projectedKm: Int?,
)
