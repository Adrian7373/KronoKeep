package com.kronokeep.app.domain.asset

import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.Peso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AssetValidatorTest {
    private val today = LocalDate.of(2026, 11, 14)
    private val valid = AssetDraft(category = AssetCategory.VEHICLE, name = "Yamaha NMAX")

    private fun errors(draft: AssetDraft) = AssetValidator.validate(draft.normalized(), today)

    @Test fun minimalDraftIsValid() {
        assertTrue(errors(valid).isEmpty())
    }

    @Test fun nameIsRequiredAndBounded() {
        assertEquals(AssetError.Required, errors(valid.copy(name = "   "))[AssetField.NAME])
        assertEquals(AssetError.TooLong(120), errors(valid.copy(name = "x".repeat(121)))[AssetField.NAME])
        assertTrue(errors(valid.copy(name = "x".repeat(120))).isEmpty())
    }

    @Test fun textLimitsMatchDatabaseChecks() {
        val e = errors(
            valid.copy(brand = "b".repeat(81), model = "m".repeat(81), serialNo = "s".repeat(121), notes = "n".repeat(2001)),
        )
        assertEquals(AssetError.TooLong(80), e[AssetField.BRAND])
        assertEquals(AssetError.TooLong(80), e[AssetField.MODEL])
        assertEquals(AssetError.TooLong(120), e[AssetField.SERIAL])
        assertEquals(AssetError.TooLong(2000), e[AssetField.NOTES])
    }

    @Test fun purchaseDateCannotBeInTheFuture() {
        assertEquals(AssetError.InFuture, errors(valid.copy(purchaseDate = today.plusDays(1)))[AssetField.PURCHASE_DATE])
        assertNull(errors(valid.copy(purchaseDate = today))[AssetField.PURCHASE_DATE])
    }

    @Test fun warrantyCannotEndBeforePurchase() {
        val draft = valid.copy(purchaseDate = LocalDate.of(2026, 1, 10), warrantyExpiresOn = LocalDate.of(2026, 1, 9))
        assertEquals(AssetError.BeforePurchase, errors(draft)[AssetField.WARRANTY])
        assertNull(errors(draft.copy(warrantyExpiresOn = LocalDate.of(2026, 1, 10)))[AssetField.WARRANTY])
        // Expired warranties are fine: you can log an old laptop.
        assertNull(errors(valid.copy(warrantyExpiresOn = LocalDate.of(2020, 1, 1)))[AssetField.WARRANTY])
    }

    @Test fun negativeMoneyAndDistancesAreRejected() {
        val e = errors(valid.copy(price = Peso(-1), currentOdometerKm = -5, weeklyKmBaseline = -1))
        assertEquals(AssetError.Negative, e[AssetField.PRICE])
        assertEquals(AssetError.Negative, e[AssetField.ODOMETER])
        assertEquals(AssetError.Negative, e[AssetField.WEEKLY_KM])
    }

    @Test fun absurdUsageIsRejected() {
        val e = errors(valid.copy(currentOdometerKm = 2_000_001, weeklyKmBaseline = 10_001))
        assertEquals(AssetError.TooLarge(2_000_000), e[AssetField.ODOMETER])
        assertEquals(AssetError.TooLarge(10_000), e[AssetField.WEEKLY_KM])
    }

    @Test fun normalizeTrimsBlanksAndDropsBaselinesForNonVehicles() {
        val draft = AssetDraft(
            category = AssetCategory.APPLIANCE,
            name = "  Aircon  ",
            brand = "  ",
            model = " FTKC25 ",
            currentOdometerKm = -10,
            weeklyKmBaseline = 50,
        ).normalized()
        assertEquals("Aircon", draft.name)
        assertNull(draft.brand)
        assertEquals("FTKC25", draft.model)
        assertNull(draft.currentOdometerKm)
        assertNull(draft.weeklyKmBaseline)
        assertTrue(AssetValidator.validate(draft, today).isEmpty())
    }
}
