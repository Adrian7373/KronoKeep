package com.kronokeep.app.domain.capture

import com.kronokeep.app.domain.model.Peso
import java.time.LocalDate

/** What a receipt photo yielded. Every field is optional; the user reviews them before anything is applied. */
data class ReceiptScan(
    val purchaseDate: LocalDate? = null,
    val store: String? = null,
    val total: Peso? = null,
    val serialNo: String? = null,
) {
    val isEmpty: Boolean get() = purchaseDate == null && store == null && total == null && serialNo == null
}

/**
 * Pulls the purchase date, store, total and serial number out of OCR rows (top to bottom, see [ReceiptLayout]).
 * Tuned for Philippine receipts: amounts like `₱1,299.00`, `MM/DD/YYYY` dates, and the BIR footer
 * (TIN, MIN, PTU, accreditation dates) that must not be mistaken for the purchase.
 */
object ReceiptParser {
    /** Dates older than this are almost certainly a permit or accreditation date, not the purchase. */
    const val MAX_AGE_YEARS = 30L
    private const val STORE_ROWS = 8
    private const val STORE_MAX = 60

    fun parse(rows: List<String>, today: LocalDate): ReceiptScan {
        val upper = rows.map { it.uppercase().replace(WHITESPACE, " ").trim() }
        return ReceiptScan(
            purchaseDate = date(upper, today),
            store = store(rows, upper),
            total = total(upper),
            serialNo = serial(upper),
        )
    }

    // --- Total ---------------------------------------------------------------------------------------

    private val MONEY = Regex("""(?<![\d.,])(?:₱|PHP|P)?\s?(\d{1,3}(?:,\d{3})+|\d+)\.(\d{2})(?!\d)""")
    private val AMOUNT_DUE = Regex("""\b(?:AMOUNT\s+DUE|TOTAL\s+DUE|GRAND\s+TOTAL|TOTAL\s+AMOUNT|AMOUNT\s+PAYABLE)\b""")
    private val TOTAL = Regex("""\bTOTAL\b""")
    private val SUBTOTAL = Regex("""\bSUB\s*-?\s*TOTAL\b""")
    private val TOTAL_NOISE = Regex(
        """\b(?:VAT|VATABLE|EXEMPT|ZERO|ITEMS?|QTY|QUANTITY|DISCOUNTS?|SAVINGS|CHANGE|CASH|TENDER(?:ED)?|POINTS|PCS)\b""",
    )
    private val LETTER = Regex("[A-Z]")

    private fun amounts(row: String): List<Peso> =
        MONEY.findAll(row).mapNotNull { Peso.parse("${it.groupValues[1]}.${it.groupValues[2]}") }.toList()

    /** "AMOUNT DUE" beats "TOTAL", which beats "SUBTOTAL". Among equals, the largest wins (tax lines are smaller). */
    private fun total(rows: List<String>): Peso? {
        val found = rows.mapIndexedNotNull { i, row ->
            val rank = when {
                AMOUNT_DUE.containsMatchIn(row) -> 0
                SUBTOTAL.containsMatchIn(row) -> if (TOTAL_NOISE.containsMatchIn(row)) null else 2
                TOTAL.containsMatchIn(row) -> if (TOTAL_NOISE.containsMatchIn(row)) null else 1
                else -> null
            } ?: return@mapIndexedNotNull null
            // The amount is usually on the same row; narrow receipts sometimes print it on the next one.
            val amount = amounts(row).lastOrNull()
                ?: rows.getOrNull(i + 1)?.takeIf { !LETTER.containsMatchIn(it) }?.let { amounts(it).lastOrNull() }
            amount?.takeIf { it.centavos > 0 }?.let { rank to it }
        }
        val best = found.minOfOrNull { it.first } ?: return null
        return found.filter { it.first == best }.maxBy { it.second.centavos }.second
    }

    // --- Date ----------------------------------------------------------------------------------------

    private const val MONTH_NAMES = "JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC"
    private val ISO = Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)""")
    private val NUMERIC = Regex("""(?<![\d/.-])(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})(?![\d/-])""")
    private val MONTH_FIRST = Regex("""\b($MONTH_NAMES)[A-Z]*\.?\s*(\d{1,2}),?\s*(\d{4})(?!\d)""")
    private val DAY_FIRST = Regex("""(?<!\d)(\d{1,2})[\s-]*($MONTH_NAMES)[A-Z]*\.?[\s,-]*(\d{4}|\d{2})(?!\d)""")
    private val DATE_NOISE = Regex("""\b(?:ISSUED|VALID|UNTIL|PTU|ACCRED\w*|PERMIT|EXPIR\w*|WARRANTY|BIRTH)\b""")
    private val DATE_HINT = Regex("""\bDATE\b|\d{1,2}:\d{2}""")

    /** Prefers a date on a row labelled DATE or carrying a time, then the first plausible one. */
    private fun date(rows: List<String>, today: LocalDate): LocalDate? {
        val earliest = today.minusYears(MAX_AGE_YEARS)
        val found = rows.filterNot { DATE_NOISE.containsMatchIn(it) }.flatMap { row ->
            datesIn(row).filter { !it.isAfter(today) && !it.isBefore(earliest) }.map { (DATE_HINT.containsMatchIn(row)) to it }
        }
        return (found.firstOrNull { it.first } ?: found.firstOrNull())?.second
    }

    private fun datesIn(row: String): List<LocalDate> = buildList {
        ISO.findAll(row).forEach { m -> m.ints().let { (y, mo, d) -> date(y, mo, d)?.let(::add) } }
        NUMERIC.findAll(row).forEach { m ->
            val (a, b, y) = m.ints()
            // Philippine receipts print month first. Only a first part above 12 means day first.
            val (month, day) = if (a > 12 && b <= 12) b to a else a to b
            date(fullYear(y), month, day)?.let(::add)
        }
        MONTH_FIRST.findAll(row).forEach { m ->
            date(m.groupValues[3].toInt(), month(m.groupValues[1]), m.groupValues[2].toInt())?.let(::add)
        }
        DAY_FIRST.findAll(row).forEach { m ->
            date(fullYear(m.groupValues[3].toInt()), month(m.groupValues[2]), m.groupValues[1].toInt())?.let(::add)
        }
    }

    private fun MatchResult.ints(): List<Int> = groupValues.drop(1).map(String::toInt)
    private fun fullYear(y: Int) = if (y < 100) 2000 + y else y
    private fun month(name: String) = MONTH_NAMES.split('|').indexOf(name.take(3)) + 1
    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    // --- Store ---------------------------------------------------------------------------------------

    private val STORE_NOISE = Regex(
        """\b(?:TIN|VAT|NON-VAT|REG|MIN|SN|PTU|ACCRED\w*|PERMIT|OFFICIAL|RECEIPT|INVOICE|CASHIER|TEL|PHONE|MOBILE|""" +
            """EMAIL|WWW|DATE|TIME|WELCOME|THANK|CUSTOMER|TRANSACTION|TERMINAL|POS|BRGY|BARANGAY|STREET|ST|AVE|""" +
            """AVENUE|ROAD|RD|BLVD|BLDG|BUILDING|FLOOR|FLR|UNIT|PROVINCE|PHILIPPINES|COPY|OPERATED|PROP|PROPRIETOR|""" +
            """PLEASE|AGAIN)\b""",
    )

    /** The first heading-like row near the top: mostly letters, not a TIN, address or receipt title. */
    private fun store(original: List<String>, rows: List<String>): String? {
        val index = rows.take(STORE_ROWS).indexOfFirst { row ->
            val chars = row.filterNot(Char::isWhitespace)
            val letters = chars.count(Char::isLetter)
            letters >= 3 &&
                letters * 10 >= chars.length * 6 &&
                row.length <= STORE_MAX &&
                '@' !in row &&
                !STORE_NOISE.containsMatchIn(row)
        }
        if (index < 0) return null
        val text = original[index].replace(WHITESPACE, " ").trim().trimEnd('.', ',', ':', ';', '-').trim()
        return if (text.any(Char::isLowerCase)) text else titleCase(text)
    }

    /** `ABENSON APPLIANCE` → `Abenson Appliance`. Two-letter words (SM, LG) stay as printed. */
    private fun titleCase(text: String): String = text.split(' ').joinToString(" ") { word ->
        if (word.count(Char::isLetter) <= 2) word else word.lowercase().replaceFirstChar(Char::uppercaseChar)
    }

    // --- Serial --------------------------------------------------------------------------------------

    /** The POS machine's own serial sits in the header next to TIN / MIN / PTU. It isn't the product's. */
    private val SERIAL_NOISE = Regex("""\b(?:MIN|PTU|TIN|ACCRED\w*|MACHINE|POS|TERMINAL|PERMIT)\b""")

    private fun serial(rows: List<String>): String? =
        rows.asSequence().filterNot { SERIAL_NOISE.containsMatchIn(it) }.firstNotNullOfOrNull(SerialPatterns::labelled)

    private val WHITESPACE = Regex("""\s+""")
}
