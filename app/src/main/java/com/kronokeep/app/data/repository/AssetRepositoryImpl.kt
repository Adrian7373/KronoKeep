package com.kronokeep.app.data.repository

import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.data.local.TransactionRunner
import com.kronokeep.app.data.local.dao.AssetDao
import com.kronokeep.app.data.local.dao.OdometerReadingDao
import com.kronokeep.app.data.local.dao.OutboxDao
import com.kronokeep.app.data.local.entity.AssetEntity
import com.kronokeep.app.data.local.entity.OdometerReadingEntity
import com.kronokeep.app.data.local.record
import com.kronokeep.app.data.local.toDomain
import com.kronokeep.app.data.local.toNewEntity
import com.kronokeep.app.data.local.withDraft
import com.kronokeep.app.domain.common.IdGenerator
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.domain.repository.AssetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssetRepositoryImpl @Inject constructor(
    private val assetDao: AssetDao,
    private val odometerDao: OdometerReadingDao,
    private val outboxDao: OutboxDao,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val userIds: UserIdProvider,
) : AssetRepository {

    override fun observeActive(): Flow<List<Asset>> =
        assetDao.observeActive().map { rows -> rows.map { it.toDomain() } }

    override fun observeArchived(): Flow<List<Asset>> =
        assetDao.observeArchived().map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: String): Flow<Asset?> = assetDao.observeById(id).map { it?.toDomain() }

    override suspend fun get(id: String): Asset? = assetDao.getById(id)?.takeIf { it.deletedAt == null }?.toDomain()

    override suspend fun create(draft: AssetDraft): String {
        val now = clock.instant()
        val entity = draft.toNewEntity(id = ids.newId(), userId = userIds.currentUserId(), now = now)
        tx.inTransaction {
            assetDao.upsert(entity)
            outboxDao.record(SyncTables.ASSETS, entity.id, now)
            // The first reading seeds the engine's km/day estimate.
            entity.currentOdometerKm?.let { km -> addReading(entity, km, now) }
        }
        return entity.id
    }

    override suspend fun update(id: String, draft: AssetDraft): Boolean = tx.inTransaction {
        val existing = assetDao.getById(id)?.takeIf { it.deletedAt == null } ?: return@inTransaction false
        val now = clock.instant()
        val updated = existing.withDraft(draft, now)
        assetDao.upsert(updated)
        outboxDao.record(SyncTables.ASSETS, id, now)
        val km = updated.currentOdometerKm
        if (km != null && km != existing.currentOdometerKm) addReading(updated, km, now)
        true
    }

    override suspend fun setArchived(id: String, archived: Boolean): Boolean = mutate(id) { now ->
        copy(archivedAt = if (archived) archivedAt ?: now else null)
    }

    override suspend fun delete(id: String): Boolean = mutate(id) { now -> copy(deletedAt = now) }

    private suspend fun mutate(id: String, change: AssetEntity.(Instant) -> AssetEntity): Boolean = tx.inTransaction {
        val existing = assetDao.getById(id)?.takeIf { it.deletedAt == null } ?: return@inTransaction false
        val now = clock.instant()
        assetDao.upsert(existing.change(now).copy(updatedAt = now, syncState = SyncState.PENDING))
        outboxDao.record(SyncTables.ASSETS, id, now)
        true
    }

    private suspend fun addReading(asset: AssetEntity, km: Int, now: Instant) {
        val reading = OdometerReadingEntity(
            id = ids.newId(),
            userId = asset.userId,
            assetId = asset.id,
            km = km,
            recordedAt = now,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncState = SyncState.PENDING,
        )
        odometerDao.upsert(reading)
        outboxDao.record(SyncTables.ODOMETER_READINGS, reading.id, now)
    }
}
