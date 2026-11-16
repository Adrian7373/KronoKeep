package com.kronokeep.app.domain.capture

import com.kronokeep.app.domain.asset.AssetValidator

/** Barcode families, mapped from ML Kit formats in the data layer. */
enum class CodeKind {
    /** EAN / UPC: identifies the product model, not the unit. */
    PRODUCT,
    /** Code 128 / 39 / 93, Codabar, ITF: what serial stickers use. */
    LINEAR,
    /** QR, Data Matrix, PDF417, Aztec. */
    MATRIX,
}

/** Where a candidate came from. Declaration order is the ranking, best first. */
enum class CandidateSource { LABELLED_TEXT, BARCODE, VIN, PLATE, PRODUCT_CODE }

data class SerialCandidate(val value: String, val source: CandidateSource) {
    /** Comparison key, so `ABC-123`, `abc 123` and `ABC123` count as one sighting. */
    val key: String get() = value.uppercase().filter(Char::isLetterOrDigit)
}

/** Text next to a serial label such as `S/N:`, `Serial No.`, `IMEI`, `VIN` or `Chassis No.`. */
object SerialPatterns {
    private const val LABEL =
        """(?:S\s?/\s?N|SN|SERIAL(?:\s*(?:NO\.?|NUMBER|#))?|IMEI\s*\d?|VIN|CHASSIS(?:\s*NO\.?)?|FRAME(?:\s*NO\.?)?)"""

    // A separator or space must follow the label, so "SNACKS" is not "SN ACKS".
    private val LABELLED = Regex("""(?<![A-Z0-9])$LABEL(?:\s*[:#.]\s*|\s+)([A-Z0-9][A-Z0-9-]{2,}[A-Z0-9])""")
    private val LABEL_PREFIX = Regex("""^$LABEL(?:\s*[:#.]\s*|\s+)""", RegexOption.IGNORE_CASE)

    /** [upperRow] must already be uppercase. Values need at least one digit to count. */
    fun labelled(upperRow: String): String? =
        LABELLED.findAll(upperRow).map { it.groupValues[1] }.firstOrNull(::plausible)

    /** `S/N:AB123` → `AB123`, in any letter case. */
    fun stripLabel(value: String): String = value.replace(LABEL_PREFIX, "")

    fun plausible(value: String): Boolean =
        value.length in 4..AssetValidator.SERIAL_MAX && value.any(Char::isDigit)
}

/** Turns barcodes and recognised text into serial-number suggestions for the scanner. */
object SerialCandidates {
    private val VIN = Regex("""(?<![A-Z0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Z0-9])""")
    /** Philippine plates, only when the row is nothing but the plate: `ABC 1234`, `123 ABC`, `AB 12345`. */
    private val PLATE = Regex("""^(?:([A-Z]{3})\s?(\d{3,4})|(\d{3})\s?([A-Z]{3})|([A-Z]{2})\s?(\d{4,5}))$""")
    private val INNER_SPACE = Regex("""\s+""")

    fun fromBarcode(raw: String, kind: CodeKind): SerialCandidate? {
        val text = raw.trim()
        if ("://" in text) return null // a product page, not a serial
        // Multi-field QR stickers ("MODEL:X\nS/N:Y") carry the serial on its own labelled line.
        if ('\n' in text || '\r' in text) {
            return text.lines().firstNotNullOfOrNull { SerialPatterns.labelled(it.uppercase().trim()) }
                ?.let { SerialCandidate(it, CandidateSource.LABELLED_TEXT) }
        }
        val value = SerialPatterns.stripLabel(text).trim()
        if (INNER_SPACE.containsMatchIn(value) || !SerialPatterns.plausible(value)) return null
        return SerialCandidate(value, if (kind == CodeKind.PRODUCT) CandidateSource.PRODUCT_CODE else CandidateSource.BARCODE)
    }

    fun fromText(rows: List<String>, vehicle: Boolean): List<SerialCandidate> = buildList {
        for (row in rows) {
            val upper = row.uppercase().replace(INNER_SPACE, " ").trim()
            SerialPatterns.labelled(upper)?.let { add(SerialCandidate(it, CandidateSource.LABELLED_TEXT)) }
            VIN.findAll(upper).map { it.value }
                .filter { v -> v.any(Char::isLetter) && v.any(Char::isDigit) }
                .forEach { add(SerialCandidate(it, CandidateSource.VIN)) }
            if (vehicle) {
                PLATE.matchEntire(upper)?.let { m ->
                    val parts = m.groupValues.drop(1).filter(String::isNotEmpty)
                    add(SerialCandidate(parts.joinToString(" "), CandidateSource.PLATE))
                }
            }
        }
    }

    /** One entry per [SerialCandidate.key], keeping the best source, best first. */
    fun rank(candidates: List<SerialCandidate>): List<SerialCandidate> =
        candidates.groupBy { it.key }.values.map { same -> same.minBy { it.source.ordinal } }.sortedBy { it.source.ordinal }
}

/**
 * Smooths live scanning. Barcodes carry checksums, so one read is enough; OCR text flickers between
 * frames, so it has to be seen [textSightings] times before it's offered.
 */
class CandidateTracker(private val textSightings: Int = 2, private val max: Int = 5) {
    private val seen = LinkedHashMap<String, Pair<SerialCandidate, Int>>()

    fun observe(frame: List<SerialCandidate>): List<SerialCandidate> {
        for (candidate in SerialCandidates.rank(frame)) {
            val previous = seen[candidate.key]
            val best = if (previous == null || candidate.source.ordinal < previous.first.source.ordinal) candidate else previous.first
            seen[candidate.key] = best to (previous?.second ?: 0) + 1
        }
        if (seen.size > MAX_TRACKED) seen.entries.removeAll { it.value.second < textSightings }
        return current()
    }

    fun current(): List<SerialCandidate> =
        SerialCandidates.rank(seen.values.filter { (c, n) -> n >= required(c) }.map { it.first }).take(max)

    fun clear() = seen.clear()

    private fun required(c: SerialCandidate) =
        if (c.source == CandidateSource.BARCODE || c.source == CandidateSource.PRODUCT_CODE) 1 else textSightings

    private companion object {
        const val MAX_TRACKED = 100
    }
}
