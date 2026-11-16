package com.kronokeep.app.domain.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialCandidatesTest {

    @Test fun linearBarcodeIsASerialAndProductCodeIsFlagged() {
        assertEquals(SerialCandidate("R58N123ABC", CandidateSource.BARCODE), SerialCandidates.fromBarcode("R58N123ABC", CodeKind.LINEAR))
        assertEquals(
            SerialCandidate("4806512345678", CandidateSource.PRODUCT_CODE),
            SerialCandidates.fromBarcode("4806512345678", CodeKind.PRODUCT),
        )
    }

    @Test fun barcodeLabelPrefixIsStrippedButSerialsStartingWithSnAreKept() {
        assertEquals("AB12345", SerialCandidates.fromBarcode("S/N:AB12345", CodeKind.LINEAR)?.value)
        assertEquals("SN12345678", SerialCandidates.fromBarcode("SN12345678", CodeKind.LINEAR)?.value)
    }

    @Test fun urlsShortCodesAndPhrasesAreRejected() {
        assertNull(SerialCandidates.fromBarcode("https://example.com/p/123", CodeKind.MATRIX))
        assertNull(SerialCandidates.fromBarcode("A1", CodeKind.LINEAR))
        assertNull(SerialCandidates.fromBarcode("ABCDEFGH", CodeKind.LINEAR)) // no digit
        assertNull(SerialCandidates.fromBarcode("HELLO 123 WORLD", CodeKind.MATRIX))
    }

    @Test fun multiFieldQrYieldsItsLabelledSerial() {
        val qr = "MODEL: AR12TXHQ\nS/N: 0B7K4ABN600123\nMFG: 2026-01"
        assertEquals(
            SerialCandidate("0B7K4ABN600123", CandidateSource.LABELLED_TEXT),
            SerialCandidates.fromBarcode(qr, CodeKind.MATRIX),
        )
    }

    @Test fun textFindsLabelledSerialsAndVins() {
        val found = SerialCandidates.fromText(listOf("Serial No. 7XK3-99812", "Chassis: MH1JFZ130PK123456"), vehicle = false)
        assertEquals(SerialCandidate("7XK3-99812", CandidateSource.LABELLED_TEXT), found[0])
        assertTrue(found.any { it.value == "MH1JFZ130PK123456" })
    }

    @Test fun platesOnlyForVehiclesAndOnlyWhenTheRowIsJustThePlate() {
        assertEquals(listOf(SerialCandidate("NAB 1234", CandidateSource.PLATE)), SerialCandidates.fromText(listOf("nab1234"), vehicle = true))
        assertEquals(listOf(SerialCandidate("123 ABC", CandidateSource.PLATE)), SerialCandidates.fromText(listOf("123 ABC"), vehicle = true))
        assertTrue(SerialCandidates.fromText(listOf("NAB 1234"), vehicle = false).isEmpty())
        assertTrue(SerialCandidates.fromText(listOf("PROMO ABC 1234 ONLY"), vehicle = true).isEmpty())
    }

    @Test fun snackDoesNotLookLikeASerialLabel() {
        assertTrue(SerialCandidates.fromText(listOf("SNACKS123 45.00"), vehicle = false).isEmpty())
    }

    @Test fun rankDedupesByKeyKeepingTheBestSource() {
        val ranked = SerialCandidates.rank(
            listOf(
                SerialCandidate("4806512345678", CandidateSource.PRODUCT_CODE),
                SerialCandidate("AB-123-45", CandidateSource.BARCODE),
                SerialCandidate("AB12345", CandidateSource.LABELLED_TEXT),
            ),
        )
        assertEquals(listOf("AB12345", "4806512345678"), ranked.map { it.value })
    }

    @Test fun trackerNeedsTwoTextSightingsButOneBarcode() {
        val tracker = CandidateTracker()
        val text = SerialCandidate("KX2A9087B", CandidateSource.LABELLED_TEXT)
        val code = SerialCandidate("R58N123ABC", CandidateSource.BARCODE)
        assertEquals(listOf(code), tracker.observe(listOf(text, code)))
        assertEquals(listOf(text, code), tracker.observe(listOf(text)))
    }

    @Test fun trackerUpgradesSourceAndCapsTheList() {
        val tracker = CandidateTracker(textSightings = 1, max = 2)
        tracker.observe(listOf(SerialCandidate("AB12345", CandidateSource.BARCODE)))
        tracker.observe(listOf(SerialCandidate("ab-12345", CandidateSource.LABELLED_TEXT)))
        tracker.observe(listOf(SerialCandidate("ZZ99999", CandidateSource.VIN), SerialCandidate("YY88888", CandidateSource.PLATE)))
        val current = tracker.current()
        assertEquals(2, current.size)
        assertEquals(CandidateSource.LABELLED_TEXT, current[0].source)
        tracker.clear()
        assertTrue(tracker.current().isEmpty())
    }
}
