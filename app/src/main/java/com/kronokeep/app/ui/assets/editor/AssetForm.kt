package com.kronokeep.app.ui.assets.editor

import com.kronokeep.app.domain.asset.AssetError
import com.kronokeep.app.domain.asset.AssetField
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.Peso
import java.time.LocalDate

/** Raw text the user typed. Converted to an [AssetDraft] only when moving on or saving. */
data class AssetForm(
    val category: AssetCategory? = null,
    val name: String = "",
    val brand: String = "",
    val model: String = "",
    val serialNo: String = "",
    val purchaseDate: LocalDate? = null,
    val price: String = "",
    val warrantyExpiresOn: LocalDate? = null,
    val odometerKm: String = "",
    val weeklyKm: String = "",
    val notes: String = "",
) {
    sealed interface Parsed {
        data class Ok(val draft: AssetDraft) : Parsed
        data class Invalid(val errors: Map<AssetField, AssetError>) : Parsed
    }

    /** Parses numbers and money. Requires a [category]; semantic rules are left to the validator. */
    fun parse(): Parsed {
        val category = checkNotNull(category) { "Pick a category first" }
        val errors = mutableMapOf<AssetField, AssetError>()
        val price: Peso? = if (price.isBlank()) {
            null
        } else {
            Peso.parse(price).also { if (it == null) errors[AssetField.PRICE] = AssetError.InvalidNumber }
        }
        val vehicle = category == AssetCategory.VEHICLE
        val odometer = if (vehicle) parseKm(odometerKm, AssetField.ODOMETER, errors) else null
        val weekly = if (vehicle) parseKm(weeklyKm, AssetField.WEEKLY_KM, errors) else null
        if (errors.isNotEmpty()) return Parsed.Invalid(errors)
        return Parsed.Ok(
            AssetDraft(
                category = category,
                name = name,
                brand = brand,
                model = model,
                serialNo = serialNo,
                purchaseDate = purchaseDate,
                price = price,
                warrantyExpiresOn = warrantyExpiresOn,
                currentOdometerKm = odometer,
                weeklyKmBaseline = weekly,
                notes = notes,
            ),
        )
    }

    private fun parseKm(text: String, field: AssetField, errors: MutableMap<AssetField, AssetError>): Int? {
        val cleaned = text.replace(",", "").replace(" ", "").lowercase().removeSuffix("km")
        if (cleaned.isEmpty()) return null
        // Negatives parse fine here and are rejected by the validator with a clearer message.
        return cleaned.toIntOrNull().also { if (it == null) errors[field] = AssetError.InvalidNumber }
    }

    companion object {
        fun from(asset: Asset) = AssetForm(
            category = asset.category,
            name = asset.name,
            brand = asset.brand.orEmpty(),
            model = asset.model.orEmpty(),
            serialNo = asset.serialNo.orEmpty(),
            purchaseDate = asset.purchaseDate,
            price = asset.price?.let { "%d.%02d".format(it.centavos / 100, it.centavos % 100) }.orEmpty(),
            warrantyExpiresOn = asset.warrantyExpiresOn,
            odometerKm = asset.currentOdometerKm?.toString().orEmpty(),
            weeklyKm = asset.weeklyKmBaseline?.toString().orEmpty(),
            notes = asset.notes.orEmpty(),
        )

        val BASELINE_FIELDS = setOf(AssetField.ODOMETER, AssetField.WEEKLY_KM)
    }
}
