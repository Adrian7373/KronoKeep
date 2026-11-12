package com.kronokeep.app.domain.repository

import com.kronokeep.app.domain.model.Report
import com.kronokeep.app.domain.model.ShareLink

/** Why a report call failed. Anything else (offline, timeouts) surfaces as [Unavailable]. */
sealed class ReportFailure(message: String) : Exception(message) {
    class CloudNotConfigured : ReportFailure("Cloud sync isn't configured")
    class NotSignedIn : ReportFailure("Not signed in")
    /** The asset or report isn't on the server (not synced yet, or deleted). */
    class NotFound : ReportFailure("Not found on the server")
    class NoServiceLogs : ReportFailure("No service history to report")
    /** The history changed on the server while the report was being built. Safe to retry. */
    class Changed : ReportFailure("The history changed while the report was being made")
    class Revoked : ReportFailure("This report was revoked")
    class Unavailable(message: String?) : ReportFailure(message ?: "Couldn't reach the server")
}

/** Generated report plus whether the server returned an existing one (same content, not revoked). */
data class GeneratedReport(val report: Report, val reused: Boolean)

/** Every remote call throws [ReportFailure]. */
interface ReportRepository {
    /** Newest first. */
    suspend fun list(assetId: String): List<Report>

    suspend fun generate(assetId: String): GeneratedReport

    /** Creates a fresh 7-day link. Re-sharing rotates the token, so older links stop working. */
    suspend fun share(reportId: String): ShareLink

    /** Marks the report revoked on the server (verify shows Revoked) and kills its share link. Logs stay locked. */
    suspend fun revoke(reportId: String)

    /** A signed URL for the owner to view the PDF; expires within 10 minutes. */
    suspend fun pdfUrl(report: Report): String

    /** Local changes for this asset (the asset, schedules, logs, readings) still waiting in the outbox. */
    suspend fun unsyncedChanges(assetId: String): Int
}
