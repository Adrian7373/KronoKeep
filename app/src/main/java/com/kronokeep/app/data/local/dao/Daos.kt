package com.kronokeep.app.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
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

    @Query("SELECT * FROM assets WHERE deletedAt IS NULL AND archivedAt IS NOT NULL ORDER BY archivedAt DESC")
    fun observeArchived(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: String): Flow<AssetEntity?>

    /** Includes soft-deleted rows; callers check `deletedAt`. */
    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun getById(id: String): AssetEntity?

    /** Rows created before any sign-in, waiting to be claimed. */
    @Query("SELECT id FROM assets WHERE userId IS NULL")
    suspend fun unownedIds(): List<String>

    @Upsert
    suspend fun upsert(asset: AssetEntity)
}

@Dao
interface MaintenanceScheduleDao {
    @Query("SELECT * FROM maintenance_schedules WHERE assetId = :assetId AND deletedAt IS NULL ORDER BY createdAt")
    fun observeForAsset(assetId: String): Flow<List<MaintenanceScheduleEntity>>

    @Query("SELECT * FROM maintenance_schedules WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<MaintenanceScheduleEntity>>

    @Query("SELECT * FROM maintenance_schedules WHERE assetId = :assetId AND deletedAt IS NULL")
    suspend fun forAsset(assetId: String): List<MaintenanceScheduleEntity>

    /** Includes soft-deleted rows; callers check `deletedAt`. */
    @Query("SELECT * FROM maintenance_schedules WHERE id = :id")
    suspend fun getById(id: String): MaintenanceScheduleEntity?

    @Query("SELECT id FROM maintenance_schedules WHERE userId IS NULL")
    suspend fun unownedIds(): List<String>

    @Query("SELECT DISTINCT assetId FROM maintenance_schedules WHERE deletedAt IS NULL")
    suspend fun assetIdsWithSchedules(): List<String>

    @Upsert
    suspend fun upsert(schedule: MaintenanceScheduleEntity)
}

/** A log row plus its schedule's task name. The join ignores `deletedAt` so history keeps names of removed schedules. */
data class ServiceLogWithTask(
    @Embedded val log: ServiceLogEntity,
    val task: String?,
)

@Dao
interface ServiceLogDao {
    @Query(
        """
        SELECT l.*, s.task AS task FROM service_logs l
        LEFT JOIN maintenance_schedules s ON s.id = l.scheduleId
        WHERE l.assetId = :assetId AND l.deletedAt IS NULL
        ORDER BY l.performedAt DESC, l.createdAt
        """,
    )
    fun observeForAsset(assetId: String): Flow<List<ServiceLogWithTask>>

    /** Includes soft-deleted rows; callers check `deletedAt`. */
    @Query("SELECT * FROM service_logs WHERE id = :id")
    suspend fun getById(id: String): ServiceLogEntity?

    @Query("SELECT id FROM service_logs WHERE userId IS NULL")
    suspend fun unownedIds(): List<String>

    @Query("SELECT * FROM service_logs WHERE scheduleId = :scheduleId AND deletedAt IS NULL ORDER BY performedAt DESC LIMIT 1")
    suspend fun latestForSchedule(scheduleId: String): ServiceLogEntity?

    @Upsert
    suspend fun upsert(log: ServiceLogEntity)
}

@Dao
interface OdometerReadingDao {
    @Query("SELECT * FROM odometer_readings WHERE assetId = :assetId AND deletedAt IS NULL ORDER BY recordedAt")
    suspend fun forAsset(assetId: String): List<OdometerReadingEntity>

    @Query("SELECT * FROM odometer_readings WHERE assetId = :assetId AND deletedAt IS NULL ORDER BY recordedAt")
    fun observeForAsset(assetId: String): Flow<List<OdometerReadingEntity>>

    @Query("SELECT * FROM odometer_readings WHERE deletedAt IS NULL ORDER BY recordedAt")
    fun observeAll(): Flow<List<OdometerReadingEntity>>

    /** Includes soft-deleted rows; callers check `deletedAt`. */
    @Query("SELECT * FROM odometer_readings WHERE id = :id")
    suspend fun getById(id: String): OdometerReadingEntity?

    @Query("SELECT id FROM odometer_readings WHERE userId IS NULL")
    suspend fun unownedIds(): List<String>

    @Upsert
    suspend fun upsert(reading: OdometerReadingEntity)
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE assetId = :assetId AND deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeForAsset(assetId: String): Flow<List<DocumentEntity>>

    /** Includes soft-deleted rows; callers check `deletedAt`. */
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: String): DocumentEntity?

    @Query("SELECT id FROM documents WHERE userId IS NULL")
    suspend fun unownedIds(): List<String>

    /** Local files still waiting for the private bucket. FAILED rows have lost their local copy. */
    @Query(
        """
        SELECT * FROM documents
        WHERE userId = :userId AND storagePath IS NULL AND localPath IS NOT NULL
          AND deletedAt IS NULL AND syncState != 'FAILED'
        ORDER BY createdAt
        """,
    )
    suspend fun pendingUploads(userId: String): List<DocumentEntity>

    @Upsert
    suspend fun upsert(document: DocumentEntity)
}

@Dao
interface OutboxDao {
    @Upsert
    suspend fun enqueue(entry: OutboxEntity)

    /** Entries still worth pushing, oldest first. Entries that hit [maxAttempts] wait for the next local edit. */
    @Query("SELECT * FROM outbox WHERE attempts < :maxAttempts ORDER BY seq")
    suspend fun pending(maxAttempts: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE tableName = :tableName AND rowId = :rowId LIMIT 1")
    suspend fun entryFor(tableName: String, rowId: String): OutboxEntity?

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE seq = :seq")
    suspend fun markAttempt(seq: Long, error: String?)

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun remove(seq: Long)

    @Query("DELETE FROM outbox WHERE tableName = :tableName AND rowId = :rowId")
    suspend fun removeFor(tableName: String, rowId: String)

    /** `seq` is AUTOINCREMENT, so this moves on every local write, even when the outbox coalesces. */
    @Query("SELECT MAX(seq) FROM outbox")
    fun observeLatestSeq(): Flow<Long?>

    @Query("SELECT MAX(seq) FROM outbox")
    suspend fun latestSeq(): Long?

    @Query("SELECT COUNT(*) FROM outbox WHERE attempts < :maxAttempts")
    fun observePendingCount(maxAttempts: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE attempts >= :maxAttempts")
    fun observeRejectedCount(maxAttempts: Int): Flow<Int>

    /** Unpushed changes to an asset or the rows a report is built from (documents aren't part of a report). */
    @Query(
        """
        SELECT COUNT(*) FROM outbox WHERE
            (tableName = 'assets' AND rowId = :assetId)
            OR (tableName = 'maintenance_schedules' AND rowId IN (SELECT id FROM maintenance_schedules WHERE assetId = :assetId))
            OR (tableName = 'service_logs' AND rowId IN (SELECT id FROM service_logs WHERE assetId = :assetId))
            OR (tableName = 'odometer_readings' AND rowId IN (SELECT id FROM odometer_readings WHERE assetId = :assetId))
        """,
    )
    suspend fun countForAsset(assetId: String): Int
}
