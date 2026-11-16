package com.kronokeep.app.domain.capture

import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.testing.fixedClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ScanReceiptUseCaseTest {
    private fun useCase(zone: ZoneId = ZoneOffset.UTC, reader: ReceiptTextReader) = ScanReceiptUseCase(reader, fixedClock(), zone)

    private fun line(text: String, top: Int, left: Int = 0) = OcrLine(text, left, top, left + 100, top + 20)

    @Test fun columnsAreRejoinedBeforeParsing() = runBlocking {
        val reader = ReceiptTextReader {
            listOf(line("TOTAL", 100), line("Date: 11/09/2026", 40), line("2,499.00", 102, left = 300))
        }
        val result = useCase(reader = reader)("content://receipt")
        result as ReceiptScanResult.Found
        assertEquals(Peso(249_900), result.scan.total)
        assertEquals(LocalDate.of(2026, 11, 9), result.scan.purchaseDate)
    }

    @Test fun emptyTextIsNothingFound() = runBlocking {
        assertEquals(ReceiptScanResult.NothingFound, useCase(reader = { emptyList() })("u"))
    }

    @Test fun readerErrorsBecomeFailed() = runBlocking {
        val result = useCase(reader = { throw IOException("decode failed") })("u")
        assertEquals(ReceiptScanResult.Failed("decode failed"), result)
    }

    @Test fun todayIsTheLocalDateNotUtc() = runBlocking {
        // At the fixed instant it's already the 14th in UTC but still the 13th at UTC-12, where the 14th is future.
        val utcDate = LocalDate.ofInstant(fixedClock().instant(), ZoneOffset.UTC)
        val row = "DATE ${utcDate.monthValue}/${utcDate.dayOfMonth}/${utcDate.year}"
        val utc = useCase(ZoneOffset.UTC, reader = { listOf(line(row, 0)) })("u")
        val behind = useCase(ZoneId.of("Etc/GMT+12"), reader = { listOf(line(row, 0)) })("u")
        assertTrue(utc is ReceiptScanResult.Found)
        assertEquals(ReceiptScanResult.NothingFound, behind)
    }
}
