package com.kronokeep.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.kronokeep.app.data.local.entity.AssetEntity
import com.kronokeep.app.data.local.entity.DocumentEntity
import com.kronokeep.app.data.local.entity.MaintenanceScheduleEntity
import com.kronokeep.app.data.local.entity.OdometerReadingEntity
import com.kronokeep.app.data.local.entity.OutboxEntity
import com.kronokeep.app.data.local.entity.ServiceLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets WHERE deletedAt IS NULL AND archivedAt IS NULL ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: String): Flow<AssetEntity?>

    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun getById(id: String): AssetEntity?

    @Upsert
    suspend fun upsert(asset: AssetEntity)
}

@Dao
interface MaintenanceScheduleDao {
    @Query("SELECT * FROM maintenance_schedules WHERE assetId = :assetId AND deletedAt IS NULL ORDER BY nextDueAt")
    fun observeForAsset(assetId: String): Flow<List<MaintenanceScheduleEntity>>

    @Upsert
    suspend fun upsert(schedule: MaintenanceScheduleEntity)
}

@Dao
interface ServiceLogDao {
    @Query("SELECT * FROM service_logs WHERE assetId = :assetId AND deletedAt IS NULL ORDER BY performedAt DESC")
    fun observeForAsset(assetId: String): Flow<List<ServiceLogEntity>>

    @Upsert
    suspend fun upsert(log: ServiceLogEntity)
}

@Dao
interface OdometerReadingDao {
    @Query("SELECT * FROM odometer_readings WHERE assetId = :assetId AND deletedAt IS NULL ORDER BY recordedAt")
    suspend fun forAsset(assetId: String): List<OdometerReadingEntity>

    @Upsert
    suspend fun upsert(reading: OdometerReadingEntity)
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE assetId = :assetId AND deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeForAsset(assetId: String): Flow<List<DocumentEntity>>

    @Upsert
    suspend fun upsert(document: DocumentEntity)
}

@Dao
interface OutboxDao {
    @Upsert
    suspend fun enqueue(entry: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY seq LIMIT :limit")
    suspend fun peek(limit: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun remove(seq: Long)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observePendingCount(): Flow<Int>
}
