package com.kronokeep.app.data.repository

import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.data.local.TransactionRunner
import com.kronokeep.app.data.local.dao.AssetDao
import com.kronokeep.app.data.local.dao.MaintenanceScheduleDao
import com.kronokeep.app.data.local.dao.OdometerReadingDao
import com.kronokeep.app.data.local.dao.OutboxDao
import com.kronokeep.app.data.local.dao.ServiceLogDao
import com.kronokeep.app.data.local.entity.AssetEntity
import com.kronokeep.app.data.local.entity.MaintenanceScheduleEntity
import com.kronokeep.app.data.local.entity.OdometerReadingEntity
import com.kronokeep.app.data.local.entity.ServiceLogEntity
import com.kronokeep.app.data.local.record
import com.kronokeep.app.data.local.toDomain
import com.kronokeep.app.data.local.toNewEntity
import com.kronokeep.app.data.local.withDraft
import com.kronokeep.app.domain.common.IdGenerator
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.MaintenanceSchedule
import com.kronokeep.app.domain.model.OdometerReading
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.model.ServiceLog
import com.kronokeep.app.domain.model.ServiceVisit
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.domain.repository.DeleteLogResult
import com.kronokeep.app.domain.repository.MaintenanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MaintenanceRepositoryImpl @Inject constructor(
    private val assetDao: AssetDao,
    private val scheduleDao: MaintenanceScheduleDao,
    private val logDao: ServiceLogDao,
    private val odometerDao: OdometerReadingDao,
    private val outboxDao: OutboxDao,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val recomputer: ScheduleRecomputer,
) : MaintenanceRepository {

    override fun observeSchedules(assetId: String): Flow<List<MaintenanceSchedule>> =
        scheduleDao.observeForAsset(assetId).map { rows -> rows.map { it.toDomain() } }

    override fun observeAllSchedules(): Flow<List<MaintenanceSchedule>> =
        scheduleDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeReadings(assetId: String): Flow<List<OdometerReading>> =
        odometerDao.observeForAsset(assetId).map { rows -> rows.map { it.toDomain() } }

    override fun observeAllReadings(): Flow<List<OdometerReading>> =
        odometerDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeServiceLogs(assetId: String): Flow<List<ServiceLog>> =
        logDao.observeForAsset(assetId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getSchedule(id: String): MaintenanceSchedule? = scheduleDao.getById(id)?.takeIf { it.deletedAt == null }?.toDomain()

    override suspend fun addSchedules(assetId: String, drafts: List<ScheduleDraft>): List<String> = tx.inTransaction {
        val asset = liveAsset(assetId) ?: return@inTransaction emptyList()
        val now = clock.instant()
        val created = drafts.map { draft ->
            // Child rows inherit the asset's owner so an offline asset and its schedules are claimed together.
            draft.toNewEntity(id = ids.newId(), assetId = assetId, userId = asset.userId, now = now).also {
                scheduleDao.upsert(it)
                outboxDao.record(SyncTables.SCHEDULES, it.id, now)
            }
        }
        recomputer.recompute(assetId, now)
        created.map { it.id }
    }

    override suspend fun updateSchedule(id: String, draft: ScheduleDraft): Boolean = tx.inTransaction {
        val existing = liveSchedule(id) ?: return@inTransaction false
        val now = clock.instant()
        scheduleDao.upsert(existing.withDraft(draft, now))
        outboxDao.record(SyncTables.SCHEDULES, id, now)
        recomputer.recompute(existing.assetId, now)
        true
    }

    override suspend fun deleteSchedule(id: String): Boolean = tx.inTransaction {
        val existing = liveSchedule(id) ?: return@inTransaction false
        val now = clock.instant()
        scheduleDao.upsert(existing.copy(deletedAt = now, updatedAt = now, syncState = SyncState.PENDING))
        outboxDao.record(SyncTables.SCHEDULES, id, now)
        true
    }

    override suspend fun logService(assetId: String, visit: ServiceVisit): List<String> = tx.inTransaction {
        val asset = liveAsset(assetId) ?: return@inTransaction emptyList()
        val now = clock.instant()
        val at = visit.performedAt

        // One row per ticked task, or a single unlinked row for an ad-hoc repair. Cost goes on the first row
        // so totals aren't double-counted.
        val targets: List<String?> = visit.scheduleIds.ifEmpty { listOf(null) }
        val logIds = targets.mapIndexed { index, scheduleId ->
            val log = ServiceLogEntity(
                id = ids.newId(),
                userId = asset.userId,
                assetId = assetId,
                scheduleId = scheduleId,
                performedAt = at,
                odometerKm = visit.odometerKm,
                costCentavos = visit.cost?.centavos.takeIf { index == 0 },
                shop = visit.shop,
                notes = visit.notes,
                lockedAt = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                syncState = SyncState.PENDING,
            )
            logDao.upsert(log)
            outboxDao.record(SyncTables.SERVICE_LOGS, log.id, now)
            log.id
        }

        // Back-dated logs older than the current "last done" don't move it backwards.
        for (scheduleId in visit.scheduleIds) {
            val s = liveSchedule(scheduleId) ?: continue
            if (s.lastDoneAt != null && at.isBefore(s.lastDoneAt)) continue
            scheduleDao.upsert(
                s.copy(lastDoneAt = at, lastDoneKm = visit.odometerKm ?: s.lastDoneKm, updatedAt = now, syncState = SyncState.PENDING),
            )
            outboxDao.record(SyncTables.SCHEDULES, s.id, now)
        }

        visit.odometerKm?.let { km -> recordReadingIfConsistent(asset, km, at, now) }
        recomputer.recompute(assetId, now)
        logIds
    }

    override suspend fun deleteServiceLog(id: String): DeleteLogResult = tx.inTransaction {
        val log = logDao.getById(id)?.takeIf { it.deletedAt == null } ?: return@inTransaction DeleteLogResult.NotFound
        if (log.lockedAt != null) return@inTransaction DeleteLogResult.Locked
        val now = clock.instant()
        logDao.upsert(log.copy(deletedAt = now, updatedAt = now, syncState = SyncState.PENDING))
        outboxDao.record(SyncTables.SERVICE_LOGS, id, now)

        // If this log set the schedule's "last done", fall back to the previous log for that task. With no earlier
        // log the schedule keeps its value: it may have come from the user's own "last done" entry.
        val schedule = log.scheduleId?.let { liveSchedule(it) }
        if (schedule != null && schedule.lastDoneAt == log.performedAt) {
            logDao.latestForSchedule(schedule.id)?.let { previous ->
                scheduleDao.upsert(
                    schedule.copy(
                        lastDoneAt = previous.performedAt,
                        lastDoneKm = previous.odometerKm ?: schedule.lastDoneKm,
                        updatedAt = now,
                        syncState = SyncState.PENDING,
                    ),
                )
                outboxDao.record(SyncTables.SCHEDULES, schedule.id, now)
            }
        }
        recomputer.recompute(log.assetId, now)
        DeleteLogResult.Deleted
    }

    override suspend fun recordOdometer(assetId: String, km: Int): Boolean = tx.inTransaction {
        val asset = liveAsset(assetId)?.takeIf { it.category == AssetCategory.VEHICLE } ?: return@inTransaction false
        val now = clock.instant()
        insertReading(asset, km, now, now)
        assetDao.upsert(asset.copy(currentOdometerKm = km, updatedAt = now, syncState = SyncState.PENDING))
        outboxDao.record(SyncTables.ASSETS, assetId, now)
        recomputer.recompute(assetId, now)
        true
    }

    override suspend fun recomputeAll(): Int {
        val now = clock.instant()
        return scheduleDao.assetIdsWithSchedules().sumOf { assetId -> tx.inTransaction { recomputer.recompute(assetId, now) } }
    }

    /**
     * A service log's odometer becomes a reading only if it fits the timeline (no earlier reading is higher and
     * no later one is lower). It moves the asset's odometer only when it's the newest value.
     */
    private suspend fun recordReadingIfConsistent(asset: AssetEntity, km: Int, at: Instant, now: Instant) {
        val readings = odometerDao.forAsset(asset.id)
        val fits = readings.none { (!it.recordedAt.isAfter(at) && it.km > km) || (!it.recordedAt.isBefore(at) && it.km < km) }
        val duplicate = readings.any { it.km == km && it.recordedAt == at }
        if (!fits || duplicate) return
        insertReading(asset, km, at, now)
        val newest = readings.none { it.recordedAt.isAfter(at) }
        if (newest && km > (asset.currentOdometerKm ?: -1)) {
            assetDao.upsert(asset.copy(currentOdometerKm = km, updatedAt = now, syncState = SyncState.PENDING))
            outboxDao.record(SyncTables.ASSETS, asset.id, now)
        }
    }

    private suspend fun insertReading(asset: AssetEntity, km: Int, at: Instant, now: Instant) {
        val reading = OdometerReadingEntity(
            id = ids.newId(),
            userId = asset.userId,
            assetId = asset.id,
            km = km,
            recordedAt = at,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncState = SyncState.PENDING,
        )
        odometerDao.upsert(reading)
        outboxDao.record(SyncTables.ODOMETER_READINGS, reading.id, now)
    }

    private suspend fun liveAsset(id: String): AssetEntity? = assetDao.getById(id)?.takeIf { it.deletedAt == null }

    private suspend fun liveSchedule(id: String): MaintenanceScheduleEntity? = scheduleDao.getById(id)?.takeIf { it.deletedAt == null }
}
