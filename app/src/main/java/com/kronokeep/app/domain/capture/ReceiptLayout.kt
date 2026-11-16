package com.kronokeep.app.domain.capture

import kotlin.math.max
import kotlin.math.min

/** One recognised line of text and its box, in upright image pixels. Free of ML Kit types so parsing stays pure. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/**
 * Rebuilds printed rows from OCR lines. ML Kit often returns a receipt's label column and amount column
 * as separate blocks ("TOTAL" in one, "1,299.00" in another), so lines are regrouped by vertical overlap
 * and read left to right.
 */
object ReceiptLayout {

    fun rows(lines: List<OcrLine>): List<String> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        lines.filter { it.text.isNotBlank() && it.height > 0 }
            .sortedBy { it.top + it.bottom }
            .forEach { line ->
                val row = rows.lastOrNull()
                if (row != null && row.any { sameRow(it, line) }) row += line else rows += mutableListOf(line)
            }
        return rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    /** Same row when the boxes overlap vertically by at least half the shorter one. Tolerates a slight skew. */
    private fun sameRow(a: OcrLine, b: OcrLine): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap * 2 >= min(a.height, b.height)
    }
}
