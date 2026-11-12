package com.kronokeep.app.domain.report

import com.kronokeep.app.domain.model.Report
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.MaintenanceRepository
import com.kronokeep.app.domain.repository.ReportFailure
import com.kronokeep.app.domain.repository.ReportRepository
import com.kronokeep.app.domain.repository.SyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

sealed interface GenerateReportResult {
    data class Generated(val report: Report, val reused: Boolean) : GenerateReportResult
    data object CloudNotConfigured : GenerateReportResult
    data object NotSignedIn : GenerateReportResult
    data object AssetMissing : GenerateReportResult
    data object NoServiceLogs : GenerateReportResult
    /** The report is built from the server copy, so local edits must land first. A sync has been requested. */
    data class NeedsSync(val pending: Int) : GenerateReportResult
    data object Changed : GenerateReportResult
    data class Failed(val message: String) : GenerateReportResult
}

/**
 * Checks what can be checked on the phone, then asks the server to build, hash and lock the report.
 * The report reflects the server's rows, so the asset's history has to be fully synced first; otherwise
 * the printout would silently miss the newest entries.
 */
class GenerateReportUseCase @Inject constructor(
    private val reports: ReportRepository,
    private val assets: AssetRepository,
    private val maintenance: MaintenanceRepository,
    private val auth: AuthRepository,
    private val sync: SyncRepository,
) {
    suspend operator fun invoke(assetId: String): GenerateReportResult {
        when (auth.state.value) {
            AuthState.Unconfigured -> return GenerateReportResult.CloudNotConfigured
            is AuthState.SignedIn -> Unit
            else -> return GenerateReportResult.NotSignedIn
        }
        val asset = assets.get(assetId) ?: return GenerateReportResult.AssetMissing
        if (maintenance.observeServiceLogs(assetId).first().isEmpty()) return GenerateReportResult.NoServiceLogs

        val pending = reports.unsyncedChanges(assetId)
        if (pending > 0 || asset.syncState != SyncState.SYNCED) {
            sync.syncNow()
            return GenerateReportResult.NeedsSync(pending.coerceAtLeast(1))
        }

        return try {
            val generated = reports.generate(assetId)
            // Pull the locks the server just set, so the History tab shows them without waiting for a periodic sync.
            if (!generated.reused) sync.syncNow()
            GenerateReportResult.Generated(generated.report, generated.reused)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReportFailure) {
            e.toResult()
        }
    }

    private fun ReportFailure.toResult(): GenerateReportResult = when (this) {
        is ReportFailure.CloudNotConfigured -> GenerateReportResult.CloudNotConfigured
        is ReportFailure.NotSignedIn -> GenerateReportResult.NotSignedIn
        is ReportFailure.NoServiceLogs -> GenerateReportResult.NoServiceLogs
        is ReportFailure.Changed -> GenerateReportResult.Changed
        // The server doesn't have the asset although Room says it's synced: a pull will sort it out.
        is ReportFailure.NotFound -> {
            sync.syncNow()
            GenerateReportResult.NeedsSync(1)
        }
        is ReportFailure.Revoked, is ReportFailure.Unavailable -> GenerateReportResult.Failed(message.orEmpty())
    }
}

/** Builds the text handed to the Android share sheet. Pure, so the wording is tested. */
object ReportShareText {
    fun build(assetName: String, url: String, expiresOn: String): String =
        "Verified maintenance record for $assetName (KronoKeep).\n" +
            "Download and verify: $url\n" +
            "This link works until $expiresOn."
}
