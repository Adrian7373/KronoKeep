package com.kronokeep.app.domain.model

/**
 * Philippine peso amount stored as whole centavos, so money never touches floating point.
 * Postgres columns are `*_centavos bigint`.
 */
@JvmInline
value class Peso(val centavos: Long) {

    /** `₱12,345.50`, or `-₱99.00` for negatives. Formatting is locale-independent on purpose. */
    fun format(): String {
        val sign = if (centavos < 0) "-" else ""
        val abs = if (centavos == Long.MIN_VALUE) throw ArithmeticException("Amount out of range") else kotlin.math.abs(centavos)
        val whole = (abs / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val cents = (abs % 100).toString().padStart(2, '0')
        return "$sign₱$whole.$cents"
    }

    override fun toString(): String = format()

    companion object {
        /**
         * Parses user input such as `1,299.5`, `₱ 850` or `12000`. Returns null for anything that
         * isn't a non-negative amount with at most two decimals.
         */
        fun parse(input: String): Peso? {
            val cleaned = input.trim().removePrefix("₱").removePrefix("PHP").trim().replace(",", "")
            if (cleaned.isEmpty() || !PATTERN.matches(cleaned)) return null
            val parts = cleaned.split('.')
            val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
            val fraction = parts.getOrNull(1)?.padEnd(2, '0')?.toLong() ?: 0L
            if (whole > MAX_WHOLE) return null
            return Peso(whole * 100 + fraction)
        }

        private val PATTERN = Regex("""\d*(\.\d{1,2})?""")
        private const val MAX_WHOLE = 999_999_999_999L
    }
}
