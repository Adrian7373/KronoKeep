package com.kronokeep.app.data.local

import com.kronokeep.app.data.local.dao.ServiceLogWithTask
import com.kronokeep.app.data.local.entity.AssetEntity
import com.kronokeep.app.data.local.entity.DocumentEntity
import com.kronokeep.app.data.local.entity.MaintenanceScheduleEntity
import com.kronokeep.app.data.local.entity.OdometerReadingEntity
import com.kronokeep.app.domain.engine.DuePrediction
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.Document
import com.kronokeep.app.domain.model.MaintenanceSchedule
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.OdometerReading
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.model.ServiceLog
import com.kronokeep.app.domain.model.SyncState
import java.time.Instant
import kotlin.math.roundToInt

fun AssetEntity.toDomain(): Asset = Asset(
    id = id,
    category = category,
    name = name,
    brand = brand,
    model = model,
    serialNo = serialNo,
    purchaseDate = purchaseDate,
    price = priceCentavos?.let(::Peso),
    warrantyExpiresOn = warrantyExpiresOn,
    currentOdometerKm = currentOdometerKm,
    weeklyKmBaseline = weeklyKmBaseline,
    notes = notes,
    archivedAt = archivedAt,
    updatedAt = updatedAt,
    syncState = syncState,
)

/** A brand-new row. `updatedAt` is provisional; the server trigger owns the canonical value. */
fun AssetDraft.toNewEntity(id: String, userId: String?, now: Instant): AssetEntity = AssetEntity(
    id = id,
    userId = userId,
    category = category,
    name = name,
    brand = brand,
    model = model,
    serialNo = serialNo,
    purchaseDate = purchaseDate,
    priceCentavos = price?.centavos,
    warrantyExpiresOn = warrantyExpiresOn,
    currentOdometerKm = currentOdometerKm,
    weeklyKmBaseline = weeklyKmBaseline,
    notes = notes,
    archivedAt = null,
    createdAt = now,
    updatedAt = now,
    deletedAt = null,
    syncState = SyncState.PENDING,
)

/** Applies editable fields only; identity, ownership, archive and delete state are kept. */
fun AssetEntity.withDraft(draft: AssetDraft, now: Instant): AssetEntity = copy(
    name = draft.name,
    brand = draft.brand,
    model = draft.model,
    serialNo = draft.serialNo,
    purchaseDate = draft.purchaseDate,
    priceCentavos = draft.price?.centavos,
    warrantyExpiresOn = draft.warrantyExpiresOn,
    currentOdometerKm = draft.currentOdometerKm,
    weeklyKmBaseline = draft.weeklyKmBaseline,
    notes = draft.notes,
    updatedAt = now,
    syncState = SyncState.PENDING,
)

fun MaintenanceScheduleEntity.toDomain(): MaintenanceSchedule = MaintenanceSchedule(
    id = id,
    assetId = assetId,
    task = task,
    intervalDays = intervalDays,
    intervalKm = intervalKm,
    lastDoneAt = lastDoneAt,
    lastDoneKm = lastDoneKm,
    nextDueAt = nextDueAt,
    nextDueKm = nextDueKm,
    confidence = confidence,
    status = status,
    updatedAt = updatedAt,
    syncState = syncState,
)

/** A brand-new schedule. Due fields start empty; the engine fills them in the same transaction. */
fun ScheduleDraft.toNewEntity(id: String, assetId: String, userId: String?, now: Instant) = MaintenanceScheduleEntity(
    id = id,
    userId = userId,
    assetId = assetId,
    task = task,
    intervalDays = intervalDays,
    intervalKm = intervalKm,
    lastDoneAt = lastDoneAt,
    lastDoneKm = lastDoneKm,
    nextDueAt = null,
    nextDueKm = null,
    confidence = null,
    status = MaintenanceStatus.OK,
    createdAt = now,
    updatedAt = now,
    deletedAt = null,
    syncState = SyncState.PENDING,
)

fun MaintenanceScheduleEntity.withDraft(draft: ScheduleDraft, now: Instant) = copy(
    task = draft.task,
    intervalDays = draft.intervalDays,
    intervalKm = draft.intervalKm,
    lastDoneAt = draft.lastDoneAt,
    lastDoneKm = draft.lastDoneKm,
    updatedAt = now,
    syncState = SyncState.PENDING,
)

/** Copies engine output onto the row. Confidence is stored to 3 decimals (it's a `real` in Postgres). */
fun MaintenanceScheduleEntity.withPrediction(p: DuePrediction) = copy(
    nextDueAt = p.nextDueAt,
    nextDueKm = p.nextDueKm,
    confidence = (p.confidence * 1000).roundToInt() / 1000.0,
    status = p.status,
)

fun ServiceLogWithTask.toDomain(): ServiceLog = ServiceLog(
    id = log.id,
    assetId = log.assetId,
    scheduleId = log.scheduleId,
    task = task,
    performedAt = log.performedAt,
    odometerKm = log.odometerKm,
    cost = log.costCentavos?.let(::Peso),
    shop = log.shop,
    notes = log.notes,
    lockedAt = log.lockedAt,
    syncState = log.syncState,
)

fun OdometerReadingEntity.toDomain() = OdometerReading(id = id, assetId = assetId, km = km, recordedAt = recordedAt)

fun DocumentEntity.toDomain(): Document = Document(
    id = id,
    assetId = assetId,
    kind = kind,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    storagePath = storagePath,
    localPath = localPath,
    createdAt = createdAt,
    syncState = syncState,
)
