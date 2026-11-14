package com.kronokeep.app.domain.asset

import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.AssetDraft
import java.time.LocalDate

enum class AssetField { NAME, BRAND, MODEL, SERIAL, PURCHASE_DATE, PRICE, WARRANTY, ODOMETER, WEEKLY_KM, NOTES }

sealed interface AssetError {
    data object Required : AssetError
    data class TooLong(val max: Int) : AssetError
    data object Negative : AssetError
    data object InFuture : AssetError
    data object BeforePurchase : AssetError
    data class TooLarge(val max: Int) : AssetError
    /** Raised by input parsing (e.g. "12k" for km), before validation. */
    data object InvalidNumber : AssetError
}

/** Same limits as the `assets` CHECK constraints, so a valid draft never bounces off the server. */
object AssetValidator {
    const val NAME_MAX = 120
    const val BRAND_MAX = 80
    const val MODEL_MAX = 80
    const val SERIAL_MAX = 120
    const val NOTES_MAX = 2000
    const val ODOMETER_MAX = 2_000_000
    const val WEEKLY_KM_MAX = 10_000

    /** Validates a [AssetDraft.normalized] draft. [today] is passed in so the rule stays deterministic. */
    fun validate(draft: AssetDraft, today: LocalDate): Map<AssetField, AssetError> = buildMap {
        when {
            draft.name.isBlank() -> put(AssetField.NAME, AssetError.Required)
            draft.name.length > NAME_MAX -> put(AssetField.NAME, AssetError.TooLong(NAME_MAX))
        }
        draft.brand?.let { if (it.length > BRAND_MAX) put(AssetField.BRAND, AssetError.TooLong(BRAND_MAX)) }
        draft.model?.let { if (it.length > MODEL_MAX) put(AssetField.MODEL, AssetError.TooLong(MODEL_MAX)) }
        draft.serialNo?.let { if (it.length > SERIAL_MAX) put(AssetField.SERIAL, AssetError.TooLong(SERIAL_MAX)) }
        draft.notes?.let { if (it.length > NOTES_MAX) put(AssetField.NOTES, AssetError.TooLong(NOTES_MAX)) }
        draft.price?.let { if (it.centavos < 0) put(AssetField.PRICE, AssetError.Negative) }
        draft.purchaseDate?.let { if (it.isAfter(today)) put(AssetField.PURCHASE_DATE, AssetError.InFuture) }
        val purchase = draft.purchaseDate
        val warranty = draft.warrantyExpiresOn
        if (purchase != null && warranty != null && warranty.isBefore(purchase)) {
            put(AssetField.WARRANTY, AssetError.BeforePurchase)
        }
        if (draft.category == AssetCategory.VEHICLE) {
            draft.currentOdometerKm?.let {
                when {
                    it < 0 -> put(AssetField.ODOMETER, AssetError.Negative)
                    it > ODOMETER_MAX -> put(AssetField.ODOMETER, AssetError.TooLarge(ODOMETER_MAX))
                }
            }
            draft.weeklyKmBaseline?.let {
                when {
                    it < 0 -> put(AssetField.WEEKLY_KM, AssetError.Negative)
                    it > WEEKLY_KM_MAX -> put(AssetField.WEEKLY_KM, AssetError.TooLarge(WEEKLY_KM_MAX))
                }
            }
        }
    }
}
