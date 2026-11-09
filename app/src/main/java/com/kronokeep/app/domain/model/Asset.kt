package com.kronokeep.app.domain.model

import java.time.Instant
import java.time.LocalDate

data class Asset(
    val id: String,
    val category: AssetCategory,
    val name: String,
    val brand: String?,
    val model: String?,
    val serialNo: String?,
    val purchaseDate: LocalDate?,
    val price: Peso?,
    val warrantyExpiresOn: LocalDate?,
    val currentOdometerKm: Int?,
    val weeklyKmBaseline: Int?,
    val notes: String?,
    val updatedAt: Instant,
    val syncState: SyncState,
)
