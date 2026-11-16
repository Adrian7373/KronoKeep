package com.kronokeep.app.domain.maintenance

import com.kronokeep.app.domain.engine.DuePrediction
import com.kronokeep.app.domain.engine.RateSource
import com.kronokeep.app.domain.engine.UsageRate
import com.kronokeep.app.domain.model.MaintenanceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DueWindowTest {
    private val now = Instant.parse("2026-11-14T00:00:00Z")
    private val day = Duration.ofDays(1)

    private fun prediction(dueInDays: Long?, uncertainty: Int?, status: MaintenanceStatus = MaintenanceStatus.OK) =
        DuePrediction(dueInDays?.let { now + day.multipliedBy(it) }, 8_000, status, 0.5, null, uncertainty, UsageRate(null, RateSource.NONE, 0.0, 0), null)

    @Test fun `no date, no window`() {
        assertNull(DueWindow.of(prediction(null, 3), now))
    }

    @Test fun `window is centred on the most likely day and ordered left to right`() {
        val w = DueWindow.of(prediction(60, 10), now)!!
        assertEquals(now + day.multipliedBy(50), w.windowStart)
        assertEquals(now + day.multipliedBy(70), w.windowEnd)
        assertTrue(w.nowFraction < w.windowStartFraction)
        assertTrue(w.windowStartFraction < w.mostLikelyFraction)
        assertTrue(w.mostLikelyFraction < w.windowEndFraction)
        // Symmetric window → the dot sits in the middle of the band.
        assertEquals((w.windowStartFraction + w.windowEndFraction) / 2, w.mostLikelyFraction, 1e-4f)
        // Padding keeps today and the window edge off the very ends of the track.
        assertEquals(DueWindow.PADDING / (1 + 2 * DueWindow.PADDING), w.nowFraction.toDouble(), 1e-4)
        assertEquals(1 - DueWindow.PADDING / (1 + 2 * DueWindow.PADDING), w.windowEndFraction.toDouble(), 1e-4)
        assertFalse(w.isPoint)
    }

    @Test fun `calendar date collapses to a point`() {
        val w = DueWindow.of(prediction(40, 0), now)!!
        assertTrue(w.isPoint)
        assertEquals(w.windowStartFraction, w.windowEndFraction)
    }

    @Test fun `short range is stretched to the minimum span`() {
        val w = DueWindow.of(prediction(2, 0), now)!!
        val span = Duration.between(w.start, w.end)
        assertTrue(span >= DueWindow.MIN_SPAN)
        // Due in 2 days on a 28+ day track: close to today, not at the far end.
        assertTrue(w.mostLikelyFraction - w.nowFraction < 0.1f)
    }

    @Test fun `overdue puts today to the right of the window`() {
        val w = DueWindow.of(prediction(-20, 5, MaintenanceStatus.OVERDUE), now)!!
        assertTrue(w.windowEndFraction < w.nowFraction)
        assertTrue(w.nowFraction < 1f)
    }

    @Test fun `negative uncertainty is treated as zero`() {
        assertTrue(DueWindow.of(prediction(10, -3), now)!!.isPoint)
    }
}
