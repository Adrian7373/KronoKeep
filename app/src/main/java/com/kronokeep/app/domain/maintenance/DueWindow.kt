package com.kronokeep.app.domain.maintenance

import com.kronokeep.app.domain.engine.DuePrediction
import java.time.Duration
import java.time.Instant

/**
 * Geometry of a due-window timeline: a track from [start] to [end] with today, the most likely due day and the
 * ± window placed on it as fractions (0..1). The window's width *is* the uncertainty, so a calendar task (exact
 * date) collapses to a point and a noisy usage estimate draws a wide band.
 */
data class DueWindow(
    val start: Instant,
    val end: Instant,
    val windowStart: Instant,
    val mostLikely: Instant,
    val windowEnd: Instant,
    val nowFraction: Float,
    val windowStartFraction: Float,
    val mostLikelyFraction: Float,
    val windowEndFraction: Float,
) {
    val isPoint: Boolean get() = windowStart == windowEnd

    companion object {
        /** The track never spans less than this, so a due-today task doesn't fill the whole bar. */
        val MIN_SPAN: Duration = Duration.ofDays(28)
        /** Breathing room on each side of the interesting range, as a share of it. */
        const val PADDING = 0.12

        /** Null when the prediction has no date (km-only schedule without a usage rate). */
        fun of(p: DuePrediction, now: Instant): DueWindow? {
            val due = p.nextDueAt ?: return null
            val half = Duration.ofDays((p.uncertaintyDays ?: 0).coerceAtLeast(0).toLong())
            val windowStart = due - half
            val windowEnd = due + half

            var lo = minOf(now, windowStart).toEpochMilli().toDouble()
            var hi = maxOf(now, windowEnd).toEpochMilli().toDouble()
            val minSpan = MIN_SPAN.toMillis().toDouble()
            if (hi - lo < minSpan) {
                // Grow forward from the earlier edge: the future is what the user is looking at.
                hi = lo + minSpan
            }
            val pad = (hi - lo) * PADDING
            lo -= pad
            hi += pad
            val span = hi - lo
            fun frac(t: Instant) = ((t.toEpochMilli() - lo) / span).toFloat().coerceIn(0f, 1f)

            return DueWindow(
                start = Instant.ofEpochMilli(lo.toLong()),
                end = Instant.ofEpochMilli(hi.toLong()),
                windowStart = windowStart,
                mostLikely = due,
                windowEnd = windowEnd,
                nowFraction = frac(now),
                windowStartFraction = frac(windowStart),
                mostLikelyFraction = frac(due),
                windowEndFraction = frac(windowEnd),
            )
        }
    }
}
