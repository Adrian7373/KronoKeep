package com.kronokeep.app.ui.assets.editor

import com.kronokeep.app.domain.asset.AssetField
import com.kronokeep.app.domain.capture.ReceiptScan
import com.kronokeep.app.domain.model.Peso

/** A value a receipt scan can fill in. The store has no column of its own, so it goes into the notes. */
enum class ReceiptField(val assetField: AssetField) {
    DATE(AssetField.PURCHASE_DATE),
    PRICE(AssetField.PRICE),
    SERIAL(AssetField.SERIAL),
    STORE(AssetField.NOTES),
}

/** Merges a reviewed [ReceiptScan] into the form. Nothing the user already typed is replaced unless they tick it. */
object ReceiptFill {

    /** Fields the scan found, in display order. */
    fun found(scan: ReceiptScan): List<ReceiptField> = buildList {
        if (scan.purchaseDate != null) add(ReceiptField.DATE)
        if (scan.total != null) add(ReceiptField.PRICE)
        if (scan.serialNo != null) add(ReceiptField.SERIAL)
        if (scan.store != null) add(ReceiptField.STORE)
    }

    /** Found fields whose form value is filled and different, so applying would overwrite it. */
    fun conflicts(form: AssetForm, scan: ReceiptScan): Set<ReceiptField> = found(scan).filterTo(mutableSetOf()) { field ->
        when (field) {
            ReceiptField.DATE -> form.purchaseDate != null && form.purchaseDate != scan.purchaseDate
            ReceiptField.PRICE -> form.price.isNotBlank() && Peso.parse(form.price) != scan.total
            ReceiptField.SERIAL -> form.serialNo.isNotBlank() && !form.serialNo.trim().equals(scan.serialNo, ignoreCase = true)
            ReceiptField.STORE -> false // appended, never overwrites
        }
    }

    /** Pre-ticked: everything found that would fill a blank, plus the store unless the notes already mention it. */
    fun defaultSelection(form: AssetForm, scan: ReceiptScan): Set<ReceiptField> {
        val conflicts = conflicts(form, scan)
        return found(scan).filterTo(mutableSetOf()) { field ->
            when (field) {
                ReceiptField.STORE -> !form.notes.contains(scan.store!!, ignoreCase = true)
                else -> field !in conflicts
            }
        }
    }

    fun apply(form: AssetForm, scan: ReceiptScan, fields: Set<ReceiptField>): AssetForm {
        var out = form
        if (ReceiptField.DATE in fields) scan.purchaseDate?.let { out = out.copy(purchaseDate = it) }
        if (ReceiptField.PRICE in fields) scan.total?.let { out = out.copy(price = AssetForm.priceText(it)) }
        if (ReceiptField.SERIAL in fields) scan.serialNo?.let { out = out.copy(serialNo = it) }
        if (ReceiptField.STORE in fields) scan.store?.let { store ->
            if (!out.notes.contains(store, ignoreCase = true)) {
                val line = "Bought at $store"
                out = out.copy(notes = if (out.notes.isBlank()) line else out.notes.trimEnd() + "\n" + line)
            }
        }
        return out
    }
}
