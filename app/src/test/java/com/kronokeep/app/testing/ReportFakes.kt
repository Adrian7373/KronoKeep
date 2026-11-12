package com.kronokeep.app.testing

import com.kronokeep.app.data.local.entity.AssetEntity
import com.kronokeep.app.data.local.entity.ServiceLogEntity
import com.kronokeep.app.data.remote.GeneratedReportDto
import com.kronokeep.app.data.remote.ReportDto
import com.kronokeep.app.data.remote.ReportRemote
import com.kronokeep.app.data.remote.ShareTokenDto
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.domain.repository.SyncRepository
import com.kronokeep.app.domain.repository.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Server side of reports: rows as PostgREST returns them (microsecond timestamps with an offset). */
class FakeReportRemote : ReportRemote {
    override val projectUrl = "https://proj.supabase.co/"
    val rows = mutableListOf<ReportDto>()
    var failWith: Exception? = null
    var reuse = false
    var generateCalls = 0
    val revoked = mutableListOf<String>()
    val signedUrls = mutableListOf<Pair<String, Long>>()

    override suspend fun listForAsset(assetId: String): List<ReportDto> {
        failWith?.let { throw it }
        return rows.filter { it.assetId == assetId }.sortedByDescending { it.generatedAt }
    }

    override suspend fun generate(assetId: String): GeneratedReportDto {
        failWith?.let { throw it }
        generateCalls++
        val id = "report-$generateCalls"
        val dto = ReportDto(
            id = id,
            assetId = assetId,
            storagePath = "user-1/$assetId/$id.pdf",
            contentHash = "ab".repeat(32),
            generatedAt = "2026-11-14T02:00:00.123456+00:00",
            logCount = 2,
        )
        if (!reuse) rows += dto
        return GeneratedReportDto(dto.id, dto.assetId, dto.storagePath, dto.contentHash, dto.generatedAt, dto.logCount, reuse)
    }

    override suspend fun share(reportId: String): ShareTokenDto {
        failWith?.let { throw it }
        return ShareTokenDto("tok123", "2026-11-21T02:00:00.5+00:00")
    }

    override suspend fun revoke(reportId: String) {
        failWith?.let { throw it }
        revoked += reportId
    }

    override suspend fun signedPdfUrl(path: String, ttlSeconds: Long): String {
        signedUrls += path to ttlSeconds
        return "https://proj.supabase.co/storage/v1/object/sign/reports/$path?token=x"
    }
}

class CountingSyncRepository : SyncRepository {
    var syncRequests = 0
    override val status: Flow<SyncStatus> = flowOf(SyncStatus(0, 0, null, false))
    override fun syncNow() {
        syncRequests++
    }
}

fun syncedAsset(id: String = "asset-1", syncState: SyncState = SyncState.SYNCED) = AssetEntity(
    id = id, userId = "user-1", category = AssetCategory.VEHICLE, name = "Click 125i", brand = "Honda", model = null,
    serialNo = null, purchaseDate = null, priceCentavos = null, warrantyExpiresOn = null, currentOdometerKm = 12_000,
    weeklyKmBaseline = null, notes = null, archivedAt = null, createdAt = NOW, updatedAt = NOW, deletedAt = null,
    syncState = syncState,
)

fun syncedLog(id: String, assetId: String = "asset-1") = ServiceLogEntity(
    id = id, userId = "user-1", assetId = assetId, scheduleId = null, performedAt = NOW, odometerKm = 11_000,
    costCentavos = 45_000, shop = "Motortrade", notes = null, lockedAt = null, createdAt = NOW, updatedAt = NOW,
    deletedAt = null, syncState = SyncState.SYNCED,
)
