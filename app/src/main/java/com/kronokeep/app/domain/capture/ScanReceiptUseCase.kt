package com.kronokeep.app.domain.capture

import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** On-device text recognition for a still image. Implemented with ML Kit in the data layer. */
fun interface ReceiptTextReader {
    /** Lines with boxes in upright image pixels. Throws if the image can't be read. */
    suspend fun read(imageUri: String): List<OcrLine>
}

sealed interface ReceiptScanResult {
    data class Found(val scan: ReceiptScan) : ReceiptScanResult
    /** Text was read but none of it looked like a date, store, total or serial. */
    data object NothingFound : ReceiptScanResult
    data class Failed(val message: String) : ReceiptScanResult
}

class ScanReceiptUseCase @Inject constructor(
    private val reader: ReceiptTextReader,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    suspend operator fun invoke(imageUri: String): ReceiptScanResult {
        val lines = try {
            reader.read(imageUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ReceiptScanResult.Failed(e.message ?: "Couldn't read the photo")
        }
        val scan = ReceiptParser.parse(ReceiptLayout.rows(lines), LocalDate.now(clock.withZone(zone)))
        return if (scan.isEmpty) ReceiptScanResult.NothingFound else ReceiptScanResult.Found(scan)
    }
}
