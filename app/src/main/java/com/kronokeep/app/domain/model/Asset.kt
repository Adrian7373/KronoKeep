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
    val archivedAt: Instant?,
    val updatedAt: Instant,
    val syncState: SyncState,
) {
    val isArchived: Boolean get() = archivedAt != null

    fun toDraft(): AssetDraft = AssetDraft(
        category = category,
        name = name,
        brand = brand,
        model = model,
        serialNo = serialNo,
        purchaseDate = purchaseDate,
        price = price,
        warrantyExpiresOn = warrantyExpiresOn,
        currentOdometerKm = currentOdometerKm,
        weeklyKmBaseline = weeklyKmBaseline,
        notes = notes,
    )
}

/** User-editable fields of an asset, as entered in the wizard or edit form. */
data class AssetDraft(
    val category: AssetCategory,
    val name: String,
    val brand: String? = null,
    val model: String? = null,
    val serialNo: String? = null,
    val purchaseDate: LocalDate? = null,
    val price: Peso? = null,
    val warrantyExpiresOn: LocalDate? = null,
    val currentOdometerKm: Int? = null,
    val weeklyKmBaseline: Int? = null,
    val notes: String? = null,
) {
    /** Trims text, blanks become null, and usage baselines are dropped for non-vehicles. */
    fun normalized(): AssetDraft {
        val vehicle = category == AssetCategory.VEHICLE
        return copy(
            name = name.trim(),
            brand = brand.clean(),
            model = model.clean(),
            serialNo = serialNo.clean(),
            notes = notes.clean(),
            currentOdometerKm = currentOdometerKm.takeIf { vehicle },
            weeklyKmBaseline = weeklyKmBaseline.takeIf { vehicle },
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
