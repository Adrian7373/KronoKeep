package com.kronokeep.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.SyncState
import java.time.Instant
import java.time.LocalDate

/*
 * Room mirror of supabase/migrations. IDs are client-generated UUID strings so rows created offline
 * never need remapping. `userId` is null until the row is claimed by a signed-in account.
 * Synced tables soft-delete through `deletedAt`; never hard-delete them.
 */

@Entity(tableName = "assets", indices = [Index("updatedAt")])
data class AssetEntity(
    @PrimaryKey val id: String,
    val userId: String?,
    val category: AssetCategory,
    val name: String,
    val brand: String?,
    val model: String?,
    val serialNo: String?,
    val purchaseDate: LocalDate?,
    val priceCentavos: Long?,
    val warrantyExpiresOn: LocalDate?,
    val currentOdometerKm: Int?,
    val weeklyKmBaseline: Int?,
    val notes: String?,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    @ColumnInfo(defaultValue = "PENDING") val syncState: SyncState,
)

@Entity(
    tableName = "maintenance_schedules",
    foreignKeys = [ForeignKey(AssetEntity::class, ["id"], ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assetId"), Index("nextDueAt")],
)
data class MaintenanceScheduleEntity(
    @PrimaryKey val id: String,
    val userId: String?,
    val assetId: String,
    val task: String,
    val intervalDays: Int?,
    val intervalKm: Int?,
    val lastDoneAt: Instant?,
    val lastDoneKm: Int?,
    val nextDueAt: Instant?,
    val nextDueKm: Int?,
    val confidence: Double?,
    val status: MaintenanceStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    @ColumnInfo(defaultValue = "PENDING") val syncState: SyncState,
)

@Entity(
    tableName = "service_logs",
    foreignKeys = [
        ForeignKey(AssetEntity::class, ["id"], ["assetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MaintenanceScheduleEntity::class, ["id"], ["scheduleId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("assetId"), Index("scheduleId")],
)
data class ServiceLogEntity(
    @PrimaryKey val id: String,
    val userId: String?,
    val assetId: String,
    val scheduleId: String?,
    val performedAt: Instant,
    val odometerKm: Int?,
    val costCentavos: Long?,
    val shop: String?,
    val notes: String?,
    /** Set when the log is included in a verified report. Locked logs are read-only everywhere. */
    val lockedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    @ColumnInfo(defaultValue = "PENDING") val syncState: SyncState,
)

@Entity(
    tableName = "odometer_readings",
    foreignKeys = [ForeignKey(AssetEntity::class, ["id"], ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["assetId", "recordedAt"])],
)
data class OdometerReadingEntity(
    @PrimaryKey val id: String,
    val userId: String?,
    val assetId: String,
    val km: Int,
    val recordedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    @ColumnInfo(defaultValue = "PENDING") val syncState: SyncState,
)

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(AssetEntity::class, ["id"], ["assetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ServiceLogEntity::class, ["id"], ["serviceLogId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("assetId"), Index("serviceLogId")],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val userId: String?,
    val assetId: String,
    val serviceLogId: String?,
    val kind: DocumentKind,
    /** `{user_id}/{asset_id}/{uuid}.{ext}` in the private `documents` bucket; null until uploaded. */
    val storagePath: String?,
    /** App-private file kept until the upload succeeds. */
    val localPath: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    @ColumnInfo(defaultValue = "PENDING") val syncState: SyncState,
)

enum class OutboxOp { UPSERT, DELETE }

/** One pending change to push. Written in the same transaction as the row it describes. */
@Entity(tableName = "outbox", indices = [Index(value = ["tableName", "rowId"])])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val tableName: String,
    val rowId: String,
    val op: OutboxOp,
    val enqueuedAt: Instant,
    val attempts: Int = 0,
    val lastError: String? = null,
)
