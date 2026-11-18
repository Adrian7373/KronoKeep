package com.kronokeep.app.data.repository

import com.kronokeep.app.data.local.dao.OutboxDao
import com.kronokeep.app.data.remote.CloudNotConfiguredException
import com.kronokeep.app.data.remote.GeneratedReportDto
import com.kronokeep.app.data.remote.ReportDto
import com.kronokeep.app.data.remote.ReportRemote
import com.kronokeep.app.data.remote.ReportRemoteException
import com.kronokeep.app.data.remote.parsePgInstant
import com.kronokeep.app.domain.document.DocumentRules
import com.kronokeep.app.domain.model.Report
import com.kronokeep.app.domain.model.ShareLink
import com.kronokeep.app.domain.repository.GeneratedReport
import com.kronokeep.app.domain.repository.ReportFailure
import com.kronokeep.app.domain.repository.ReportRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class ReportRepositoryImpl @Inject constructor(
    private val remote: ReportRemote,
    private val outboxDao: OutboxDao,
) : ReportRepository {

    override suspend fun list(assetId: String): List<Report> = mapped {
        remote.listForAsset(assetId).map { it.toDomain() }
    }

    override suspend fun generate(assetId: String): GeneratedReport = mapped {
        val dto = remote.generate(assetId)
        GeneratedReport(dto.toDomain(), dto.reused)
    }

    override suspend fun share(reportId: String): ShareLink = mapped {
        val dto = remote.share(reportId)
        ShareLink(ReportLinks.share(remote.projectUrl, reportId, dto.token), parsePgInstant(dto.expiresAt))
    }

    override suspend fun revoke(reportId: String) = mapped { remote.revoke(reportId) }

    override suspend fun pdfUrl(report: Report): String = mapped {
        remote.signedPdfUrl(report.storagePath, DocumentRules.SIGNED_URL_TTL_SECONDS)
    }

    override suspend fun unsyncedChanges(assetId: String): Int = outboxDao.countForAsset(assetId)

    private suspend fun <T> mapped(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ReportFailure) {
        throw e
    } catch (_: CloudNotConfiguredException) {
        throw ReportFailure.CloudNotConfigured()
    } catch (e: ReportRemoteException) {
        throw e.toFailure()
    } catch (e: Exception) {
        // Raw transport messages (URLs, headers) never reach the UI; the default copy is used instead.
        throw ReportFailure.Unavailable(null)
    }
}

/** Maps backend errors (generate-report codes, Postgres errcodes from the RPCs) to domain failures. */
internal fun ReportRemoteException.toFailure(): ReportFailure = when {
    status == 401 || code == "unauthorized" || code == "28000" -> ReportFailure.NotSignedIn()
    code == "no_service_logs" || status == 422 -> ReportFailure.NoServiceLogs()
    code == "changed" || code == "40001" || status == 409 -> ReportFailure.Changed()
    // share_report raises 22023 for a revoked report.
    code == "22023" -> ReportFailure.Revoked()
    code == "not_found" || code == "P0002" || status == 404 -> ReportFailure.NotFound()
    else -> ReportFailure.Unavailable(message)
}

internal fun ReportDto.toDomain() = Report(
    id = id,
    assetId = assetId,
    storagePath = storagePath,
    contentHash = contentHash,
    generatedAt = parsePgInstant(generatedAt),
    revokedAt = revokedAt?.let(::parsePgInstant),
    shareExpiresAt = shareExpiresAt?.let(::parsePgInstant),
    logCount = logCount,
)

internal fun GeneratedReportDto.toDomain() = Report(
    id = id,
    assetId = assetId,
    storagePath = storagePath,
    contentHash = contentHash,
    generatedAt = parsePgInstant(generatedAt),
    revokedAt = null,
    shareExpiresAt = null,
    logCount = logCount,
)

/** Public links. Both go through `verify-report`; the share link adds the token that unlocks the download. */
object ReportLinks {
    fun verify(projectUrl: String, reportId: String): String =
        "${projectUrl.trimEnd('/')}/functions/v1/verify-report?id=$reportId"

    fun share(projectUrl: String, reportId: String, token: String): String = "${verify(projectUrl, reportId)}&t=$token"
}
