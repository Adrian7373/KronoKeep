package com.kronokeep.app.ui.assets.editor

import com.kronokeep.app.domain.capture.ReceiptScan
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.Peso
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReceiptFillTest {
    private val scan = ReceiptScan(
        purchaseDate = LocalDate.of(2026, 11, 9),
        store = "Abenson Appliance",
        total = Peso(3_599_900),
        serialNo = "KX2A9087B",
    )
    private val blank = AssetForm(category = AssetCategory.APPLIANCE, name = "Aircon")

    @Test fun blankFormTakesEverythingByDefault() {
        val selected = ReceiptFill.defaultSelection(blank, scan)
        assertEquals(ReceiptField.entries.toSet(), selected)
        val filled = ReceiptFill.apply(blank, scan, selected)
        assertEquals(LocalDate.of(2026, 11, 9), filled.purchaseDate)
        assertEquals("35999.00", filled.price)
        assertEquals("KX2A9087B", filled.serialNo)
        assertEquals("Bought at Abenson Appliance", filled.notes)
    }

    @Test fun typedValuesAreConflictsAndNotPreTicked() {
        val typed = blank.copy(price = "34,000", serialNo = "OTHER1", purchaseDate = LocalDate.of(2026, 11, 9))
        assertEquals(setOf(ReceiptField.PRICE, ReceiptField.SERIAL), ReceiptFill.conflicts(typed, scan))
        assertEquals(setOf(ReceiptField.DATE, ReceiptField.STORE), ReceiptFill.defaultSelection(typed, scan))
    }

    @Test fun samePriceWrittenDifferentlyIsNotAConflict() {
        assertEquals(emptySet<ReceiptField>(), ReceiptFill.conflicts(blank.copy(price = "35,999", serialNo = "kx2a9087b"), scan))
    }

    @Test fun storeIsAppendedOnceToExistingNotes() {
        val withNotes = blank.copy(notes = "Bedroom unit  ")
        val once = ReceiptFill.apply(withNotes, scan, setOf(ReceiptField.STORE))
        assertEquals("Bedroom unit\nBought at Abenson Appliance", once.notes)
        assertEquals(once, ReceiptFill.apply(once, scan, setOf(ReceiptField.STORE)))
        assertEquals(emptySet<ReceiptField>(), ReceiptFill.defaultSelection(once, ReceiptScan(store = "abenson appliance")))
    }

    @Test fun onlyFoundFieldsAreListedAndUntickedOnesAreLeftAlone() {
        val partial = ReceiptScan(total = Peso(100))
        assertEquals(listOf(ReceiptField.PRICE), ReceiptFill.found(partial))
        assertEquals(blank, ReceiptFill.apply(blank, scan, emptySet()))
    }
}
