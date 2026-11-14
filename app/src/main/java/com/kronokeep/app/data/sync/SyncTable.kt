package com.kronokeep.app.data.sync

import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.data.local.dao.AssetDao
import com.kronokeep.app.data.local.dao.DocumentDao
import com.kronokeep.app.data.local.dao.MaintenanceScheduleDao
import com.kronokeep.app.data.local.dao.OdometerReadingDao
import com.kronokeep.app.data.local.dao.ServiceLogDao
import com.kronokeep.app.data.local.entity.AssetEntity
import com.kronokeep.app.data.local.entity.DocumentEntity
import com.kronokeep.app.data.local.entity.MaintenanceScheduleEntity
import com.kronokeep.app.data.local.entity.OdometerReadingEntity
import com.kronokeep.app.data.local.entity.ServiceLogEntity
import com.kronokeep.app.data.remote.AssetDto
import com.kronokeep.app.data.remote.DocumentDto
import com.kronokeep.app.data.remote.OdometerReadingDto
import com.kronokeep.app.data.remote.ScheduleDto
import com.kronokeep.app.data.remote.ServiceLogDto
import com.kronokeep.app.data.remote.SyncJson
import com.kronokeep.app.data.remote.parsePgInstant
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.SyncState
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Whether a pending row can go to the server now. */
enum class PushReadiness {
    READY,
    /** Not yet (a document still waiting for its upload). */
    WAIT,
    /** Never: the server has no use for it (a document deleted or lost before upload). */
    LOCAL_ONLY,
}

/** A local row as the push step sees it: type-erased so one engine can drive every table. */
data class LocalRow(
    val id: String,
    val userId: String?,
    /** Room stores millis; compare against server timestamps truncated the same way. */
    val updatedAt: Instant,
    val readiness: PushReadiness,
    val json: JsonObject,
    /** A storage object to remove once this row's soft delete has reached the server. */
    val orphanedObject: String? = null,
)

sealed interface ApplyResult {
    /** [assetId] is set when the change can move due dates, so the caller recomputes that asset. */
    data class Applied(val assetId: String?) : ApplyResult
    /** A parent row hasn't been pulled yet; Room's foreign keys would reject this one. */
    data object MissingParent : ApplyResult
}

/**
 * One synced table: how to read and write its Room rows and map them to the PostgREST shape.
 * Rows applied from the server are always SYNCED; local-only columns (a document's `localPath`) are kept.
 */
abstract class SyncTable<E : Any, D : Any>(val name: String, private val serializer: KSerializer<D>) {
    protected abstract suspend fun get(id: String): E?
    protected abstract suspend fun put(row: E)
    abstract suspend fun unownedIds(): List<String>

    protected abstract fun E.meta(): Meta
    protected abstract fun E.owned(userId: String): E
    protected abstract fun E.withSyncState(state: SyncState): E
    protected abstract fun E.toDto(): D
    protected abstract fun D.toEntity(local: E?): E

    protected open fun E.readiness(): PushReadiness = PushReadiness.READY
    protected open fun E.orphanedObject(): String? = null
    protected open fun E.assetId(): String? = null
    protected open suspend fun E.parentsPresent(): Boolean = true

    /** Columns only the server writes. Sending them would at best be ignored, at worst trip a trigger. */
    protected open val serverOwnedKeys: Set<String> = setOf("updated_at")

    /** A remote row that must beat any local edit regardless of timestamps (a log locked by a report). */
    open fun remoteAlwaysWins(remote: JsonObject): Boolean = false

    protected data class Meta(val id: String, val userId: String?, val updatedAt: Instant)

    suspend fun localUpdatedAt(id: String): Instant? = get(id)?.meta()?.updatedAt

    suspend fun loadForPush(id: String): LocalRow? {
        val row = get(id) ?: return null
        val meta = row.meta()
        val readiness = row.readiness()
        // A row can't be encoded before it has an owner; it won't be pushed in that state anyway.
        val json = if (meta.userId != null && readiness == PushReadiness.READY) encode(row) else JsonObject(emptyMap())
        return LocalRow(meta.id, meta.userId, meta.updatedAt, readiness, json, row.orphanedObject())
    }

    suspend fun applyRemote(remote: JsonObject): ApplyResult {
        val dto = SyncJson.decodeFromJsonElement(serializer, remote)
        val local = get(remote.rowId())
        val row = dto.toEntity(local)
        if (!row.parentsPresent()) return ApplyResult.MissingParent
        put(row)
        return ApplyResult.Applied(row.assetId())
    }

    /** Stamps an offline row with its new owner. Returns false if the row is gone or already owned. */
    suspend fun claim(id: String, userId: String): Boolean {
        val row = get(id)?.takeIf { it.meta().userId == null } ?: return false
        put(row.owned(userId).withSyncState(SyncState.PENDING))
        return true
    }

    suspend fun markFailed(id: String) {
        get(id)?.let { put(it.withSyncState(SyncState.FAILED)) }
    }

    private fun encode(row: E): JsonObject =
        JsonObject(SyncJson.encodeToJsonElement(serializer, row.toDto()).jsonObject - serverOwnedKeys)
}

fun JsonObject.rowId(): String = getValue("id").jsonPrimitive.content
fun JsonObject.rowUpdatedAt(): Instant = parsePgInstant(getValue("updated_at").jsonPrimitive.content)

/** LWW: the server copy wins only if it was written after the local edit. Room keeps millis, so compare at millis. */
fun remoteIsNewer(remoteUpdatedAt: Instant, localUpdatedAt: Instant): Boolean =
    remoteUpdatedAt.truncatedTo(ChronoUnit.MILLIS).isAfter(localUpdatedAt)

/** Room stores millis; truncating here keeps LWW comparisons identical in Room and in tests. */
private fun Instant?.storedMillis(): Instant = (this ?: Instant.EPOCH).truncatedTo(ChronoUnit.MILLIS)

class AssetSyncTable @Inject constructor(private val dao: AssetDao) :
    SyncTable<AssetEntity, AssetDto>(SyncTables.ASSETS, AssetDto.serializer()) {
    override suspend fun get(id: String) = dao.getById(id)
    override suspend fun put(row: AssetEntity) = dao.upsert(row)
    override suspend fun unownedIds() = dao.unownedIds()
    override fun AssetEntity.meta() = Meta(id, userId, updatedAt)
    override fun AssetEntity.owned(userId: String) = copy(userId = userId)
    override fun AssetEntity.withSyncState(state: SyncState) = copy(syncState = state)
    override fun AssetEntity.assetId(): String = id

    override fun AssetEntity.toDto() = AssetDto(
        id = id, userId = requireNotNull(userId), category = category.wire, name = name, brand = brand, model = model,
        serialNo = serialNo, purchaseDate = purchaseDate, priceCentavos = priceCentavos,
        warrantyExpiresOn = warrantyExpiresOn, currentOdometerKm = currentOdometerKm,
        weeklyKmBaseline = weeklyKmBaseline, notes = notes, archivedAt = archivedAt, createdAt = createdAt,
        deletedAt = deletedAt,
    )

    override fun AssetDto.toEntity(local: AssetEntity?) = AssetEntity(
        id = id, userId = userId, category = AssetCategory.fromWire(category), name = name, brand = brand,
        model = model, serialNo = serialNo, purchaseDate = purchaseDate, priceCentavos = priceCentavos,
        warrantyExpiresOn = warrantyExpiresOn, currentOdometerKm = currentOdometerKm,
        weeklyKmBaseline = weeklyKmBaseline, notes = notes, archivedAt = archivedAt, createdAt = createdAt,
        updatedAt = updatedAt.storedMillis(), deletedAt = deletedAt, syncState = SyncState.SYNCED,
    )
}

class ScheduleSyncTable @Inject constructor(
    private val dao: MaintenanceScheduleDao,
    private val assets: AssetDao,
) : SyncTable<MaintenanceScheduleEntity, ScheduleDto>(SyncTables.SCHEDULES, ScheduleDto.serializer()) {
    override suspend fun get(id: String) = dao.getById(id)
    override suspend fun put(row: MaintenanceScheduleEntity) = dao.upsert(row)
    override suspend fun unownedIds() = dao.unownedIds()
    override fun MaintenanceScheduleEntity.meta() = Meta(id, userId, updatedAt)
    override fun MaintenanceScheduleEntity.owned(userId: String) = copy(userId = userId)
    override fun MaintenanceScheduleEntity.withSyncState(state: SyncState) = copy(syncState = state)
    override fun MaintenanceScheduleEntity.assetId(): String = assetId
    override suspend fun MaintenanceScheduleEntity.parentsPresent() = assets.getById(assetId) != null

    override fun MaintenanceScheduleEntity.toDto() = ScheduleDto(
        id = id, userId = requireNotNull(userId), assetId = assetId, task = task, intervalDays = intervalDays,
        intervalKm = intervalKm, lastDoneAt = lastDoneAt, lastDoneKm = lastDoneKm, nextDueAt = nextDueAt,
        nextDueKm = nextDueKm, confidence = confidence, status = status.wire, createdAt = createdAt,
        deletedAt = deletedAt,
    )

    override fun ScheduleDto.toEntity(local: MaintenanceScheduleEntity?) = MaintenanceScheduleEntity(
        id = id, userId = userId, assetId = assetId, task = task, intervalDays = intervalDays,
        intervalKm = intervalKm, lastDoneAt = lastDoneAt, lastDoneKm = lastDoneKm, nextDueAt = nextDueAt,
        nextDueKm = nextDueKm, confidence = confidence, status = MaintenanceStatus.fromWire(status),
        createdAt = createdAt, updatedAt = updatedAt.storedMillis(), deletedAt = deletedAt, syncState = SyncState.SYNCED,
    )
}

class OdometerSyncTable @Inject constructor(
    private val dao: OdometerReadingDao,
    private val assets: AssetDao,
) : SyncTable<OdometerReadingEntity, OdometerReadingDto>(SyncTables.ODOMETER_READINGS, OdometerReadingDto.serializer()) {
    override suspend fun get(id: String) = dao.getById(id)
    override suspend fun put(row: OdometerReadingEntity) = dao.upsert(row)
    override suspend fun unownedIds() = dao.unownedIds()
    override fun OdometerReadingEntity.meta() = Meta(id, userId, updatedAt)
    override fun OdometerReadingEntity.owned(userId: String) = copy(userId = userId)
    override fun OdometerReadingEntity.withSyncState(state: SyncState) = copy(syncState = state)
    override fun OdometerReadingEntity.assetId(): String = assetId
    override suspend fun OdometerReadingEntity.parentsPresent() = assets.getById(assetId) != null

    override fun OdometerReadingEntity.toDto() = OdometerReadingDto(
        id = id, userId = requireNotNull(userId), assetId = assetId, km = km, recordedAt = recordedAt,
        createdAt = createdAt, deletedAt = deletedAt,
    )

    override fun OdometerReadingDto.toEntity(local: OdometerReadingEntity?) = OdometerReadingEntity(
        id = id, userId = userId, assetId = assetId, km = km, recordedAt = recordedAt, createdAt = createdAt,
        updatedAt = updatedAt.storedMillis(), deletedAt = deletedAt, syncState = SyncState.SYNCED,
    )
}

class ServiceLogSyncTable @Inject constructor(
    private val dao: ServiceLogDao,
    private val assets: AssetDao,
    private val schedules: MaintenanceScheduleDao,
) : SyncTable<ServiceLogEntity, ServiceLogDto>(SyncTables.SERVICE_LOGS, ServiceLogDto.serializer()) {
    override suspend fun get(id: String) = dao.getById(id)
    override suspend fun put(row: ServiceLogEntity) = dao.upsert(row)
    override suspend fun unownedIds() = dao.unownedIds()
    override fun ServiceLogEntity.meta() = Meta(id, userId, updatedAt)
    override fun ServiceLogEntity.owned(userId: String) = copy(userId = userId)
    override fun ServiceLogEntity.withSyncState(state: SyncState) = copy(syncState = state)
    // A log moves "last done", which the server-side copy of the schedule already reflects; no recompute needed.
    override suspend fun ServiceLogEntity.parentsPresent() =
        assets.getById(assetId) != null && (scheduleId == null || schedules.getById(scheduleId) != null)

    /** `locked_at` is set only by generate-report; the lock trigger would reject any push anyway. */
    override val serverOwnedKeys = setOf("updated_at", "locked_at")

    override fun remoteAlwaysWins(remote: JsonObject): Boolean = remote["locked_at"].let { it != null && it != JsonNull }

    override fun ServiceLogEntity.toDto() = ServiceLogDto(
        id = id, userId = requireNotNull(userId), assetId = assetId, scheduleId = scheduleId,
        performedAt = performedAt, odometerKm = odometerKm, costCentavos = costCentavos, shop = shop, notes = notes,
        createdAt = createdAt, deletedAt = deletedAt,
    )

    override fun ServiceLogDto.toEntity(local: ServiceLogEntity?) = ServiceLogEntity(
        id = id, userId = userId, assetId = assetId, scheduleId = scheduleId, performedAt = performedAt,
        odometerKm = odometerKm, costCentavos = costCentavos, shop = shop, notes = notes, lockedAt = lockedAt,
        createdAt = createdAt, updatedAt = updatedAt.storedMillis(), deletedAt = deletedAt, syncState = SyncState.SYNCED,
    )
}

class DocumentSyncTable @Inject constructor(
    private val dao: DocumentDao,
    private val assets: AssetDao,
    private val logs: ServiceLogDao,
) : SyncTable<DocumentEntity, DocumentDto>(SyncTables.DOCUMENTS, DocumentDto.serializer()) {
    override suspend fun get(id: String) = dao.getById(id)
    override suspend fun put(row: DocumentEntity) = dao.upsert(row)
    override suspend fun unownedIds() = dao.unownedIds()
    override fun DocumentEntity.meta() = Meta(id, userId, updatedAt)
    override fun DocumentEntity.owned(userId: String) = copy(userId = userId)
    override fun DocumentEntity.withSyncState(state: SyncState) = copy(syncState = state)
    override suspend fun DocumentEntity.parentsPresent() =
        assets.getById(assetId) != null && (serviceLogId == null || logs.getById(serviceLogId) != null)

    /** `storage_path` is NOT NULL on the server, so a row waits until its upload has set it. */
    override fun DocumentEntity.readiness() = when {
        storagePath != null -> PushReadiness.READY
        deletedAt != null || syncState == SyncState.FAILED -> PushReadiness.LOCAL_ONLY
        else -> PushReadiness.WAIT
    }

    override fun DocumentEntity.orphanedObject(): String? = storagePath.takeIf { deletedAt != null }

    override fun DocumentEntity.toDto() = DocumentDto(
        id = id, userId = requireNotNull(userId), assetId = assetId, serviceLogId = serviceLogId, kind = kind.wire,
        storagePath = requireNotNull(storagePath), mimeType = mimeType, sizeBytes = sizeBytes, sha256 = sha256,
        createdAt = createdAt, deletedAt = deletedAt,
    )

    /** Keeps this phone's not-yet-needed local copy path; the server never knows about it. */
    override fun DocumentDto.toEntity(local: DocumentEntity?) = DocumentEntity(
        id = id, userId = userId, assetId = assetId, serviceLogId = serviceLogId, kind = DocumentKind.fromWire(kind),
        storagePath = storagePath, localPath = local?.localPath, mimeType = mimeType, sizeBytes = sizeBytes,
        sha256 = sha256, createdAt = createdAt, updatedAt = updatedAt.storedMillis(), deletedAt = deletedAt,
        syncState = SyncState.SYNCED,
    )
}

/** Every synced table, parents before children, so pushes satisfy server FKs and pulls satisfy Room's. */
class SyncTableRegistry @Inject constructor(
    assets: AssetSyncTable,
    schedules: ScheduleSyncTable,
    readings: OdometerSyncTable,
    logs: ServiceLogSyncTable,
    documents: DocumentSyncTable,
) {
    val ordered: List<SyncTable<*, *>> = listOf(assets, schedules, readings, logs, documents)
}
