package com.kronokeep.app.ui.assets.editor

import com.kronokeep.app.domain.asset.AssetError
import com.kronokeep.app.domain.asset.AssetField
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.domain.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AssetFormTest {
    private val vehicle = AssetForm(category = AssetCategory.VEHICLE, name = "Aerox")

    private fun ok(form: AssetForm) = (form.parse() as AssetForm.Parsed.Ok).draft
    private fun errors(form: AssetForm) = (form.parse() as AssetForm.Parsed.Invalid).errors

    @Test fun parsesMoneyAndKm() {
        val draft = ok(vehicle.copy(price = "₱ 129,900.50", odometerKm = "12,345 km", weeklyKm = " 120 "))
        assertEquals(Peso(12_990_050), draft.price)
        assertEquals(12_345, draft.currentOdometerKm)
        assertEquals(120, draft.weeklyKmBaseline)
    }

    @Test fun blanksBecomeNull() {
        val draft = ok(vehicle)
        assertNull(draft.price)
        assertNull(draft.currentOdometerKm)
        assertNull(draft.weeklyKmBaseline)
    }

    @Test fun garbageNumbersAreFieldErrors() {
        val e = errors(vehicle.copy(price = "12k", odometerKm = "abc", weeklyKm = "1.5"))
        assertEquals(AssetError.InvalidNumber, e[AssetField.PRICE])
        assertEquals(AssetError.InvalidNumber, e[AssetField.ODOMETER])
        assertEquals(AssetError.InvalidNumber, e[AssetField.WEEKLY_KM])
    }

    @Test fun baselinesIgnoredForNonVehicles() {
        val draft = ok(AssetForm(category = AssetCategory.APPLIANCE, name = "Ref", odometerKm = "abc"))
        assertNull(draft.currentOdometerKm)
    }

    @Test fun fromAssetRoundTripsThroughParse() {
        val asset = Asset(
            id = "a", category = AssetCategory.VEHICLE, name = "Click", brand = "Honda", model = null, serialNo = "ABC 123",
            purchaseDate = LocalDate.of(2025, 1, 2), price = Peso(8_990_005), warrantyExpiresOn = null,
            currentOdometerKm = 5_000, weeklyKmBaseline = 100, notes = null, archivedAt = null,
            updatedAt = Instant.EPOCH, syncState = SyncState.SYNCED,
        )
        val form = AssetForm.from(asset)
        assertEquals("89900.05", form.price)
        assertEquals(asset.toDraft(), ok(form).normalized())
    }
}
