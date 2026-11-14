package com.kronokeep.app.testing

import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.MaintenanceSchedule
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.OdometerReading
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.model.ServiceLog
import com.kronokeep.app.domain.model.ServiceVisit
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.domain.repository.DeleteLogResult
import com.kronokeep.app.domain.repository.MaintenanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Minimal in-memory repositories for Compose flow tests. The real ones are covered by JVM tests. */
class InMemoryAssetRepository : AssetRepository {
    val drafts = mutableMapOf<String, AssetDraft>()
    private val flow = MutableStateFlow<Map<String, Asset>>(emptyMap())

    private fun AssetDraft.toAsset(id: String) = Asset(
        id = id, category = category, name = name, brand = brand, model = model, serialNo = serialNo,
        purchaseDate = purchaseDate, price = price, warrantyExpiresOn = warrantyExpiresOn,
        currentOdometerKm = currentOdometerKm, weeklyKmBaseline = weeklyKmBaseline, notes = notes,
        archivedAt = null, updatedAt = Instant.EPOCH, syncState = SyncState.PENDING,
    )

    override fun observeActive(): Flow<List<Asset>> = flow.map { it.values.toList() }
    override fun observeArchived(): Flow<List<Asset>> = flow.map { emptyList() }
    override fun observe(id: String): Flow<Asset?> = flow.map { it[id] }
    override suspend fun get(id: String): Asset? = flow.value[id]

    override suspend fun create(draft: AssetDraft): String {
        val id = "asset-${drafts.size + 1}"
        drafts[id] = draft
        flow.value = flow.value + (id to draft.toAsset(id))
        return id
    }

    override suspend fun update(id: String, draft: AssetDraft): Boolean {
        if (id !in drafts) return false
        drafts[id] = draft
        flow.value = flow.value + (id to draft.toAsset(id))
        return true
    }

    override suspend fun setArchived(id: String, archived: Boolean) = id in drafts
    override suspend fun delete(id: String) = drafts.remove(id) != null
}

class InMemoryMaintenanceRepository : MaintenanceRepository {
    private val schedules = MutableStateFlow<List<MaintenanceSchedule>>(emptyList())
    private val readings = MutableStateFlow<List<OdometerReading>>(emptyList())
    val added = mutableListOf<Pair<String, ScheduleDraft>>()
    val visits = mutableListOf<Pair<String, ServiceVisit>>()

    override fun observeSchedules(assetId: String) = schedules.map { all -> all.filter { it.assetId == assetId } }
    override fun observeAllSchedules() = schedules
    override fun observeReadings(assetId: String) = readings.map { all -> all.filter { it.assetId == assetId } }
    override fun observeAllReadings() = readings
    override fun observeServiceLogs(assetId: String): Flow<List<ServiceLog>> = MutableStateFlow(emptyList())
    override suspend fun getSchedule(id: String) = schedules.value.firstOrNull { it.id == id }

    override suspend fun addSchedules(assetId: String, drafts: List<ScheduleDraft>): List<String> = drafts.map { d ->
        val id = "schedule-${added.size + 1}"
        added += assetId to d
        schedules.value = schedules.value + MaintenanceSchedule(
            id, assetId, d.task, d.intervalDays, d.intervalKm, d.lastDoneAt, d.lastDoneKm,
            null, null, null, MaintenanceStatus.OK, Instant.EPOCH, SyncState.PENDING,
        )
        id
    }

    override suspend fun updateSchedule(id: String, draft: ScheduleDraft) = false
    override suspend fun deleteSchedule(id: String) = false

    override suspend fun logService(assetId: String, visit: ServiceVisit): List<String> {
        visits += assetId to visit
        return List(maxOf(1, visit.scheduleIds.size)) { "log-${visits.size}-$it" }
    }

    override suspend fun deleteServiceLog(id: String) = DeleteLogResult.NotFound
    override suspend fun recordOdometer(assetId: String, km: Int) = false
    override suspend fun recomputeAll() = 0
}
