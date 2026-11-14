package com.kronokeep.app.domain.engine

import com.kronokeep.app.domain.model.MaintenanceStatus
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Predicts when maintenance is due. Pure and deterministic: `now` is always a parameter.
 *
 * Mirrored by `supabase/functions/_shared/engine.ts`; both must pass the JSON fixtures in `engine-fixtures/`.
 * All arithmetic is done in epoch milliseconds as doubles and rounded to whole seconds, so the
 * two implementations agree exactly.
 *
 * **Usage rate (km/day).** Consecutive readings form intervals (Δkm / Δdays). Each interval is weighted by
 * its length times a 30-day half-life decay on its age, so a month-long gap counts more than two readings a
 * day apart and recent driving counts more than last year's. Readings less than a day after the previous
 * one are skipped, and a reading lower than the previous one (odometer swap or typo) restarts the series.
 * With no usable interval the rate falls back to the weekly baseline ÷ 7.
 *
 * **Confidence** = count × consistency × recency:
 * `(1 − 0.5^intervals) × 1 / (1 + cv) × 0.5^(daysSinceLastReading / 30)`, where cv is the weighted
 * coefficient of variation of the interval rates. A baseline-only rate gets a flat 0.3.
 *
 * **Due point.** The calendar trigger is `lastDoneAt + intervalDays`. The usage trigger is the day the
 * odometer reaches `lastDoneKm + intervalKm`, projected from the latest reading at the estimated rate.
 * The earlier one wins. A calendar due date is exact (confidence 1); a usage date carries the rate's
 * confidence, except when a recorded reading already passed the due km (that's observed, so 1).
 */
object MaintenanceEngine {
    const val HALF_LIFE_DAYS = 30.0
    const val RECENCY_HALF_LIFE_DAYS = 30.0
    const val MIN_INTERVAL_DAYS = 1.0
    const val BASELINE_CONFIDENCE = 0.3
    const val DUE_SOON_DAYS = 14.0
    const val DUE_SOON_FRACTION = 0.10

    private const val DAY_MS = 86_400_000.0

    fun evaluate(terms: ScheduleTerms, usage: UsageInput, now: Instant): DuePrediction =
        evaluate(terms, usage, usageRate(usage, now), now)

    /** Same as above with a precomputed [rate], so one asset's schedules share a single rate estimate. */
    fun evaluate(terms: ScheduleTerms, usage: UsageInput, rate: UsageRate, now: Instant): DuePrediction {
        val nowMs = now.ms
        val kmPerDay = rate.kmPerDay?.takeIf { it > 0.0 }
        val anchor = anchor(usage, now)

        val calendarDueMs = if (terms.intervalDays != null && terms.lastDoneAt != null) {
            terms.lastDoneAt.ms + terms.intervalDays * DAY_MS
        } else {
            null
        }

        val nextDueKm = if (terms.intervalKm != null && terms.lastDoneKm != null) terms.lastDoneKm + terms.intervalKm else null
        val projectedKm = anchor?.let { it.km + (kmPerDay ?: 0.0) * max(0.0, (nowMs - it.at.ms) / DAY_MS) }

        var usageDueMs: Double? = null
        var usageConfidence = 0.0
        if (nextDueKm != null && anchor != null) {
            val remainingAtAnchor = (nextDueKm - anchor.km).toDouble()
            if (remainingAtAnchor <= 0.0) {
                // A recorded reading is already past the due km: overdue is a fact, not a forecast.
                usageDueMs = if (kmPerDay != null) anchor.at.ms + remainingAtAnchor / kmPerDay * DAY_MS else anchor.at.ms
                usageConfidence = 1.0
            } else if (kmPerDay != null) {
                usageDueMs = anchor.at.ms + remainingAtAnchor / kmPerDay * DAY_MS
                usageConfidence = rate.confidence
            }
        }

        val trigger = when {
            calendarDueMs != null && usageDueMs != null -> if (calendarDueMs <= usageDueMs) DueTrigger.CALENDAR else DueTrigger.USAGE
            calendarDueMs != null -> DueTrigger.CALENDAR
            usageDueMs != null -> DueTrigger.USAGE
            else -> null
        }
        val nextDueMs = when (trigger) {
            DueTrigger.CALENDAR -> calendarDueMs
            DueTrigger.USAGE -> usageDueMs
            null -> null
        }
        val confidence = when (trigger) {
            DueTrigger.CALENDAR -> 1.0
            DueTrigger.USAGE -> usageConfidence
            null -> 0.0
        }

        val status = when {
            nextDueMs != null && nextDueMs <= nowMs -> MaintenanceStatus.OVERDUE
            isDueSoon(nowMs, nextDueMs, calendarDueMs, terms, nextDueKm, projectedKm) -> MaintenanceStatus.DUE_SOON
            else -> MaintenanceStatus.OK
        }

        val uncertaintyDays = when {
            trigger == null || nextDueMs == null -> null
            confidence >= 1.0 -> 0
            else -> ceil(abs(nextDueMs - nowMs) / DAY_MS * (1.0 - confidence)).toInt()
        }

        return DuePrediction(
            nextDueAt = nextDueMs?.let(::toInstant),
            nextDueKm = nextDueKm,
            status = status,
            confidence = confidence,
            trigger = trigger,
            uncertaintyDays = uncertaintyDays,
            rate = rate,
            projectedKm = projectedKm?.roundToInt(),
        )
    }

    /** Time-decay EWMA of km/day, or the weekly baseline when there's no usable interval. */
    fun usageRate(usage: UsageInput, now: Instant): UsageRate {
        val nowMs = now.ms
        val sorted = usage.readings.sortedWith(compareBy<OdometerPoint>({ it.at }, { it.km }))
        val intervals = intervals(sorted)
        if (intervals.isEmpty()) return baseline(usage.weeklyKmBaseline)

        var sumW = 0.0
        var sumWR = 0.0
        val weighted = intervals.map { (from, to) ->
            val days = (to.at.ms - from.at.ms) / DAY_MS
            val rate = (to.km - from.km) / days
            val ageDays = max(0.0, (nowMs - (from.at.ms + to.at.ms) / 2.0) / DAY_MS)
            val w = days * 0.5.pow(ageDays / HALF_LIFE_DAYS)
            sumW += w
            sumWR += w * rate
            w to rate
        }
        val mean = sumWR / sumW
        var sumVar = 0.0
        for ((w, r) in weighted) sumVar += w * (r - mean) * (r - mean)
        val cv = if (mean > 0.0) sqrt(sumVar / sumW) / mean else 0.0

        val sinceLastDays = max(0.0, (nowMs - sorted.last().at.ms) / DAY_MS)
        val confidence = (1.0 - 0.5.pow(intervals.size)) *
            (1.0 / (1.0 + cv)) *
            0.5.pow(sinceLastDays / RECENCY_HALF_LIFE_DAYS)

        return UsageRate(kmPerDay = mean, source = RateSource.EWMA, confidence = confidence.coerceIn(0.0, 1.0), intervals = intervals.size)
    }

    private fun baseline(weeklyKm: Int?): UsageRate =
        if (weeklyKm != null && weeklyKm > 0) {
            UsageRate(kmPerDay = weeklyKm / 7.0, source = RateSource.BASELINE, confidence = BASELINE_CONFIDENCE, intervals = 0)
        } else {
            UsageRate(kmPerDay = null, source = RateSource.NONE, confidence = 0.0, intervals = 0)
        }

    /** Pairs of readings the rate is computed from. [sorted] is ordered by time, then km. */
    private fun intervals(sorted: List<OdometerPoint>): List<Pair<OdometerPoint, OdometerPoint>> {
        if (sorted.size < 2) return emptyList()
        val out = mutableListOf<Pair<OdometerPoint, OdometerPoint>>()
        var anchor = sorted.first()
        for (reading in sorted.drop(1)) {
            when {
                reading.km < anchor.km -> anchor = reading // odometer replaced or corrected: start over
                (reading.at.ms - anchor.at.ms) / DAY_MS < MIN_INTERVAL_DAYS -> Unit // too close to measure a rate
                else -> {
                    out += anchor to reading
                    anchor = reading
                }
            }
        }
        return out
    }

    /**
     * Where the usage projection starts: the latest reading, unless the asset's odometer field is ahead of it
     * (or there are no readings), in which case that value counts as read just now.
     */
    internal fun anchor(usage: UsageInput, now: Instant): OdometerPoint? {
        val latest = usage.readings.maxWithOrNull(compareBy<OdometerPoint>({ it.at }, { it.km }))
        val current = usage.currentOdometerKm
        return when {
            latest == null -> current?.let { OdometerPoint(it, now) }
            current != null && current > latest.km -> OdometerPoint(current, now)
            else -> latest
        }
    }

    private fun isDueSoon(
        nowMs: Double,
        nextDueMs: Double?,
        calendarDueMs: Double?,
        terms: ScheduleTerms,
        nextDueKm: Int?,
        projectedKm: Double?,
    ): Boolean {
        if (nextDueMs != null && nextDueMs - nowMs <= DUE_SOON_DAYS * DAY_MS) return true
        if (calendarDueMs != null && terms.intervalDays != null &&
            calendarDueMs - nowMs <= DUE_SOON_FRACTION * terms.intervalDays * DAY_MS
        ) {
            return true
        }
        return nextDueKm != null && projectedKm != null && terms.intervalKm != null &&
            nextDueKm - projectedKm <= DUE_SOON_FRACTION * terms.intervalKm
    }

    private val Instant.ms: Double get() = toEpochMilli().toDouble()

    private fun toInstant(ms: Double): Instant = Instant.ofEpochSecond(Math.round(ms / 1000.0))
}
