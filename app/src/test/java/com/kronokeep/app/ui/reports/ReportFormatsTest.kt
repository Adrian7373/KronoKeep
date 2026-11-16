package com.kronokeep.app.ui.reports

import com.kronokeep.app.domain.model.Report
import com.kronokeep.app.domain.report.GenerateReportResult
import com.kronokeep.app.domain.report.ReportShareText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class ReportFormatsTest {
    private val manila = ZoneId.of("Asia/Manila")
    private val now = Instant.parse("2026-11-16T02:00:00Z")
    private val report = Report(
        id = "r1", assetId = "a1", storagePath = "u/a1/r1.pdf", contentHash = "0123456789abcdef".repeat(4),
        generatedAt = now, revokedAt = null, shareExpiresAt = null, logCount = 3,
    )

    @Test fun `status shows valid or the revocation date`() {
        assertEquals("Valid", ReportFormats.status(report, manila))
        assertEquals("Revoked 16 Nov 2026", ReportFormats.status(report.copy(revokedAt = now), manila))
    }

    @Test fun `share line covers never shared, active, expired and revoked`() {
        assertEquals("Not shared yet", ReportFormats.shareLine(report, now, manila))
        val shared = report.copy(shareExpiresAt = now.plus(Duration.ofDays(7)))
        assertEquals("Shared · link works until 23 Nov 2026", ReportFormats.shareLine(shared, now, manila))
        assertEquals("Share link expired", ReportFormats.shareLine(shared, now.plus(Duration.ofDays(7)), manila))
        assertEquals("Link disabled", ReportFormats.shareLine(shared.copy(revokedAt = now), now, manila))
    }

    @Test fun `a share link is active only before expiry and while not revoked`() {
        val shared = report.copy(shareExpiresAt = now.plusSeconds(1))
        assertTrue(shared.isShareActive(now))
        assertFalse(shared.isShareActive(now.plusSeconds(1)))
        assertFalse(shared.copy(revokedAt = now).isShareActive(now))
    }

    @Test fun `short hash groups the first 16 hex digits`() {
        assertEquals("0123 4567 89ab cdef", report.shortHash)
    }

    @Test fun `generate messages`() {
        assertEquals(
            "Report ready. Its 3 service entries are now locked.",
            ReportFormats.message(GenerateReportResult.Generated(report, reused = false)),
        )
        assertTrue(ReportFormats.message(GenerateReportResult.Generated(report, reused = true)).startsWith("Nothing has changed"))
        assertTrue(ReportFormats.message(GenerateReportResult.NeedsSync(1)).startsWith("Syncing 1 change first."))
        assertTrue(ReportFormats.message(GenerateReportResult.NeedsSync(4)).startsWith("Syncing 4 changes first."))
        assertEquals("1 service entry", ReportFormats.entries(1))
        assertTrue(ReportFormats.message(GenerateReportResult.Failed("")).startsWith("Couldn't generate"))
    }

    @Test fun `share text names the asset, link and expiry`() {
        val text = ReportShareText.build("Click 125i", "https://x/verify-report?id=r1&t=t", "23 Nov 2026")
        assertTrue(text.contains("Click 125i"))
        assertTrue(text.contains("https://x/verify-report?id=r1&t=t"))
        assertTrue(text.endsWith("This link works until 23 Nov 2026."))
    }
}
