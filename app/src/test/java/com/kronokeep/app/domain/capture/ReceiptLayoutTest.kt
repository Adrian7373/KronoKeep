package com.kronokeep.app.domain.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptLayoutTest {
    private fun line(text: String, left: Int, top: Int, right: Int = left + 100, height: Int = 20) =
        OcrLine(text, left, top, right, top + height)

    @Test fun labelAndAmountColumnsJoinIntoOneRow() {
        // ML Kit returns the label block first, then the amount block: out of reading order.
        val lines = listOf(
            line("STORE", 10, 0),
            line("SUBTOTAL", 10, 40),
            line("TOTAL", 10, 70),
            line("900.00", 300, 42),
            line("1,008.00", 300, 68),
        )
        assertEquals(listOf("STORE", "SUBTOTAL 900.00", "TOTAL 1,008.00"), ReceiptLayout.rows(lines))
    }

    @Test fun slightlySkewedRowStillJoinsButNeighboursDont() {
        val lines = listOf(line("TOTAL", 10, 100), line("50.00", 300, 108), line("CASH", 10, 125))
        assertEquals(listOf("TOTAL 50.00", "CASH"), ReceiptLayout.rows(lines))
    }

    @Test fun blankAndDegenerateLinesAreDropped() {
        val lines = listOf(line("  ", 0, 0), OcrLine("x", 0, 10, 10, 10), line("OK", 0, 30))
        assertEquals(listOf("OK"), ReceiptLayout.rows(lines))
    }
}
