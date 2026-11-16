package com.kronokeep.app.domain.capture

import com.kronokeep.app.domain.model.Peso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {
    private val today = LocalDate.of(2026, 11, 16)
    private fun parse(vararg rows: String) = ReceiptParser.parse(rows.toList(), today)

    /** Typical appliance-store sales invoice, BIR header and footer included. */
    @Test fun applianceStoreInvoice() {
        val scan = parse(
            "ABENSON APPLIANCE",
            "SM City North EDSA, Quezon City",
            "VAT REG TIN: 000-123-456-00000",
            "MIN: 12345678 SN: POS9981234",
            "SALES INVOICE",
            "Date: 11/09/2026 14:32",
            "SAMSUNG INVERTER AC 1.5HP 1 35,999.00",
            "S/N: KX2A9087B",
            "SUBTOTAL 35,999.00",
            "VATABLE SALES 32,141.96",
            "VAT AMOUNT 3,857.04",
            "TOTAL AMOUNT DUE ₱35,999.00",
            "CASH 40,000.00",
            "CHANGE 4,001.00",
            "ACCRED NO. 0001 DATE ISSUED 01/15/2020 VALID UNTIL 01/14/2025",
        )
        assertEquals(LocalDate.of(2026, 11, 9), scan.purchaseDate)
        assertEquals("Abenson Appliance", scan.store)
        assertEquals(Peso(3_599_900), scan.total)
        assertEquals("KX2A9087B", scan.serialNo)
    }

    @Test fun totalOnTheNextRowAndPlainTotalLabel() {
        val scan = parse("ACE HARDWARE", "TOTAL", "1,250.50", "CASH 2,000.00")
        assertEquals(Peso(125_050), scan.total)
    }

    @Test fun totalBeatsSubtotalAndIgnoresItemCounts() {
        val scan = parse("SUBTOTAL 900.00", "TOTAL ITEMS 3", "TOTAL 1,008.00", "TOTAL SAVINGS 92.00")
        assertEquals(Peso(100_800), scan.total)
    }

    @Test fun subtotalIsTheLastResort() {
        assertEquals(Peso(45_000), parse("SUB-TOTAL 450.00").total)
    }

    @Test fun amountsNeedTwoDecimalsSoQuantitiesArentTotals() {
        assertNull(parse("TOTAL QTY 2", "TOTAL 2").total)
    }

    @Test fun monthNameAndIsoDates() {
        assertEquals(LocalDate.of(2026, 10, 3), parse("Oct 3, 2026").purchaseDate)
        assertEquals(LocalDate.of(2026, 10, 3), parse("03-OCT-2026 10:15 AM").purchaseDate)
        assertEquals(LocalDate.of(2026, 10, 3), parse("2026-10-03").purchaseDate)
    }

    @Test fun numericDatesAreMonthFirstUnlessTheyCantBe() {
        assertEquals(LocalDate.of(2026, 3, 4), parse("03/04/2026").purchaseDate)
        assertEquals(LocalDate.of(2026, 4, 25), parse("25/04/26").purchaseDate)
    }

    @Test fun futureAndAncientDatesAreIgnored() {
        assertNull(parse("12/25/2026").purchaseDate)
        assertNull(parse("01/01/1990").purchaseDate)
    }

    @Test fun labelledDateWinsOverAnEarlierOne() {
        val scan = parse("Member since 01/02/2020", "DATE: 11/01/2026")
        assertEquals(LocalDate.of(2026, 11, 1), scan.purchaseDate)
    }

    @Test fun permitDatesInTheFooterAreNotThePurchase() {
        assertNull(parse("PTU NO. FP012020 DATE ISSUED 01/15/2020").purchaseDate)
    }

    @Test fun storeSkipsTinAddressAndReceiptTitle() {
        val scan = parse("OFFICIAL RECEIPT", "VAT REG TIN 123-456-789-000", "123 Rizal Ave., Brgy. 5", "Honda Motorcycle Shop")
        assertEquals("Honda Motorcycle Shop", scan.store)
    }

    @Test fun twoLetterBrandsStayUppercase() {
        assertEquals("SM Appliance Center Inc", parse("SM APPLIANCE CENTER INC.").store)
    }

    @Test fun posMachineSerialIsNotTheProductSerial() {
        assertNull(parse("MIN 1234567 SN: ABC123456", "PTU S/N: XYZ98765").serialNo)
        assertEquals("358240051111110", parse("IMEI: 358240051111110").serialNo)
        assertEquals("C02XK1ABJG5H", parse("Serial No.: C02XK1ABJG5H").serialNo)
    }

    @Test fun nothingUsefulIsEmpty() {
        assertTrue(parse("THANK YOU", "PLEASE COME AGAIN").isEmpty)
        assertTrue(ReceiptParser.parse(emptyList(), today).isEmpty)
    }
}
