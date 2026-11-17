package com.kronokeep.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PesoTest {

    @Test fun formatsWithGroupingAndTwoDecimals() {
        assertEquals("₱0.00", Peso(0).format())
        assertEquals("₱0.05", Peso(5).format())
        assertEquals("₱999.99", Peso(99_999).format())
        assertEquals("₱1,000.00", Peso(100_000).format())
        assertEquals("₱12,345.50", Peso(1_234_550).format())
        assertEquals("₱1,234,567.89", Peso(123_456_789).format())
    }

    @Test fun formatsNegatives() {
        assertEquals("-₱99.00", Peso(-9_900).format())
    }

    @Test fun formatsCompact() {
        assertEquals("₱0", Peso(0).formatCompact())
        assertEquals("₱850", Peso(85_000).formatCompact())
        assertEquals("₱851", Peso(85_050).formatCompact())
        assertEquals("₱9,999", Peso(999_900).formatCompact())
        assertEquals("₱45.2K", Peso(4_523_400).formatCompact())
        assertEquals("₱45K", Peso(4_500_000).formatCompact())
        assertEquals("₱350K", Peso(34_960_000).formatCompact())
        assertEquals("₱1M", Peso(99_960_000).formatCompact())
        assertEquals("₱1.28M", Peso(128_000_000).formatCompact())
        assertEquals("₱2M", Peso(200_000_000).formatCompact())
        assertEquals("-₱850", Peso(-85_000).formatCompact())
    }

    @Test fun parsesCommonInput() {
        assertEquals(Peso(129_950), Peso.parse("1,299.50"))
        assertEquals(Peso(129_950), Peso.parse("1299.5"))
        assertEquals(Peso(85_000), Peso.parse("₱ 850"))
        assertEquals(Peso(1_200_000), Peso.parse("PHP12000"))
        assertEquals(Peso(50), Peso.parse(".5"))
    }

    @Test fun rejectsInvalidInput() {
        assertNull(Peso.parse(""))
        assertNull(Peso.parse("abc"))
        assertNull(Peso.parse("-5"))
        assertNull(Peso.parse("1.234"))
        assertNull(Peso.parse("."))
        assertNull(Peso.parse("9999999999999"))
    }

    @Test fun roundTrips() {
        listOf(0L, 1L, 99L, 100L, 123_456_789L).forEach { centavos ->
            assertEquals(Peso(centavos), Peso.parse(Peso(centavos).format()))
        }
    }
}
