package com.kronokeep.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ConvertersTest {
    private val converters = Converters()

    @Test fun instantRoundTripsAtMillisecondPrecision() {
        val instant = Instant.parse("2026-11-14T08:30:15.123Z")
        assertEquals(instant, converters.longToInstant(converters.instantToLong(instant)))
    }

    @Test fun dateRoundTrips() {
        val date = LocalDate.of(2027, 2, 28)
        assertEquals(date, converters.longToDate(converters.dateToLong(date)))
    }

    @Test fun nullsPassThrough() {
        assertNull(converters.instantToLong(null))
        assertNull(converters.longToInstant(null))
        assertNull(converters.dateToLong(null))
        assertNull(converters.longToDate(null))
    }
}
