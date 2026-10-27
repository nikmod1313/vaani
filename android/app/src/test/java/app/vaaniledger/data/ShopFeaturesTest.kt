package app.vaaniledger.data

import app.vaaniledger.extract.CategoryLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report's product grouping, the khata backup file, and the UPI ID
 * check -- the three places where a wrong answer is quietly wrong for a
 * long time before anyone notices.
 */
class ShopFeaturesTest {

    // ---- product keys -------------------------------------------------------

    @Test
    fun `every spelling of a thing is one product`() {
        val keys = listOf("aloo", "potato", "आलू", "aalu").map { CategoryLexicon.productKey(it, null) }.toSet()
        assertEquals(setOf("aloo"), keys)
        assertEquals("cheeni", CategoryLexicon.productKey("sugar", null))
    }

    /** Grouped by noun alone, toor and moong dal were one product labelled with whichever came first. */
    @Test
    fun `a variety is a different product`() {
        assertEquals("toor dal", CategoryLexicon.productKey("dal", "toor"))
        assertEquals("moong dal", CategoryLexicon.productKey("daal", "moong"))
    }

    @Test
    fun `a colour or size rolls up to the garment`() {
        assertEquals("kurti", CategoryLexicon.productKey("kurti", "blue"))
        assertEquals("kurti", CategoryLexicon.productKeyFromDisplay("blue kurti"))
        assertEquals("toor dal", CategoryLexicon.productKeyFromDisplay("toor dal"))
        assertEquals("dolo 650", CategoryLexicon.productKeyFromDisplay("dolo 650"))
    }

    // ---- khata export -------------------------------------------------------

    private fun tx(amount: Double, direction: String, customer: String, items: String = "") = TransactionEntity(
        shopId = "s", customerName = customer, amount = amount, mode = "udhaar",
        direction = direction, items = items, occurredAt = 0L,
    )

    @Test
    fun `the export is a spreadsheet excel reads in devanagari`() {
        val csv = KhataExport.csv(listOf(tx(250.0, "sale", "रवि")))
        assertTrue("UTF-8 byte-order mark", csv.startsWith("﻿"))
        assertTrue(csv.lines()[0].endsWith("Date,Time,Customer,Type,Mode,Amount,Items,Source,Synced"))
        assertTrue(csv.contains("रवि"))
    }

    /** Summing one customer's Amount column must give what they owe. */
    @Test
    fun `payments are negative so a column sum is the balance`() {
        val csv = KhataExport.csv(listOf(tx(500.0, "sale", "Rahul"), tx(200.0, "payment", "Rahul")))
        val amounts = csv.lines().drop(1).filter { it.isNotBlank() }.map { it.split(",")[5].toDouble() }
        assertEquals(300.0, amounts.sum(), 0.001)
    }

    @Test
    fun `a comma in a name stays in one cell`() {
        val row = KhataExport.csv(listOf(tx(10.0, "sale", "Verma, R."))).lines()[1]
        assertTrue(row.contains("\"Verma, R.\""))
    }

    // ---- UPI ID -------------------------------------------------------------

    @Test
    fun `a upi id is name at bank`() {
        assertTrue(ShopSettings.isValidUpiId("sharmakirana@okaxis"))
        assertTrue(ShopSettings.isValidUpiId("9876543210@ybl"))
        assertTrue(ShopSettings.isValidUpiId("  Sharma.Kirana@OKHDFCBANK "))
        assertFalse(ShopSettings.isValidUpiId("sharmakirana"))
        assertFalse(ShopSettings.isValidUpiId("sharma kirana@okaxis"))
        assertFalse(ShopSettings.isValidUpiId("@okaxis"))
    }
}
