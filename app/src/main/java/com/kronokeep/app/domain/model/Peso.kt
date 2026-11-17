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

    /**
     * Short form for headline figures: `₱850`, `₱12,500`, `₱45.2K`, `₱1.28M`. Rounded half-up, trailing
     * zeros dropped (`₱2M`, not `₱2.00M`). Centavos are dropped; use [format] where they matter.
     */
    fun formatCompact(): String {
        val sign = if (centavos < 0) "-" else ""
        val pesos = java.math.BigDecimal.valueOf(centavos).abs().movePointLeft(2)
        fun scaled(divisor: Long, decimals: Int, suffix: String): String =
            pesos.divide(java.math.BigDecimal.valueOf(divisor))
                .setScale(decimals, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + suffix
        val body = when {
            // From 999,500 up, "K" would round to "1000K".
            pesos >= java.math.BigDecimal.valueOf(999_500) -> scaled(1_000_000, 2, "M")
            pesos >= java.math.BigDecimal.valueOf(100_000) -> scaled(1_000, 0, "K")
            pesos >= java.math.BigDecimal.valueOf(10_000) -> scaled(1_000, 1, "K")
            else -> Peso(pesos.setScale(0, java.math.RoundingMode.HALF_UP).toLong() * 100).format().removePrefix("₱").removeSuffix(".00")
        }
        return "$sign₱$body"
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
