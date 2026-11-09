package com.kronokeep.app.data.local

import com.kronokeep.app.data.local.entity.AssetEntity
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.Peso

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
    updatedAt = updatedAt,
    syncState = syncState,
)
