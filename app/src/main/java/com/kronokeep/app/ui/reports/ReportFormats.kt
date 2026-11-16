package com.kronokeep.app.ui.reports

import com.kronokeep.app.domain.model.Report
import com.kronokeep.app.domain.report.GenerateReportResult
import com.kronokeep.app.ui.components.Formats
import java.time.Instant
import java.time.ZoneId

/** User-facing wording for reports. Pure, so it's covered by JVM tests. */
object ReportFormats {

    fun status(report: Report, zone: ZoneId = ZoneId.systemDefault()): String =
        report.revokedAt?.let { "Revoked ${Formats.date(it, zone)}" } ?: "Valid"

    fun shareLine(report: Report, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String = when {
        report.isRevoked -> "Link disabled"
        report.isShareActive(now) -> "Shared · link works until ${Formats.date(report.shareExpiresAt!!, zone)}"
        report.shareExpiresAt != null -> "Share link expired"
        else -> "Not shared yet"
    }

    fun entries(count: Int): String = if (count == 1) "1 service entry" else "$count service entries"

    fun message(result: GenerateReportResult): String = when (result) {
        is GenerateReportResult.Generated ->
            if (result.reused) {
                "Nothing has changed since your last report, so that one is still current."
            } else {
                "Report ready. Its ${entries(result.report.logCount)} are now locked."
            }
        GenerateReportResult.CloudNotConfigured -> "Reports need cloud sync, which isn't set up in this build."
        GenerateReportResult.NotSignedIn -> "Sign in to generate a verified report."
        GenerateReportResult.AssetMissing -> "This asset no longer exists."
        GenerateReportResult.NoServiceLogs -> "Log at least one service first. The report is your service history."
        is GenerateReportResult.NeedsSync ->
            "Syncing ${if (result.pending == 1) "1 change" else "${result.pending} changes"} first. " +
                "Try again once this asset shows Synced."
        GenerateReportResult.Changed -> "Your history changed while the report was being made. Try again."
        is GenerateReportResult.Failed -> result.message.ifBlank { "Couldn't generate the report. Check your connection." }
    }
}
