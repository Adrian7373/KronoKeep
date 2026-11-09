package com.kronokeep.app.data.local

import com.kronokeep.app.data.local.entity.AssetEntity
import com.kronokeep.app.data.local.entity.DocumentEntity
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.Document
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.domain.model.SyncState
import java.time.Instant

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
