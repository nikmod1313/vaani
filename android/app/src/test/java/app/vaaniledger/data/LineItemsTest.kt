package app.vaaniledger.data

import app.vaaniledger.net.Confidence
import app.vaaniledger.net.Extraction
import app.vaaniledger.net.LineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovering line items from the labels written before the table existed.
 *
 * This is the backfill that decides what the product charts show for every
 * entry a shop already had, so a wrong reading here is a wrong "best
 * seller" -- a number the shopkeeper might reorder stock against.
 */
class LineItemsTest {

    private fun tx(items: String, amount: Double = 0.0) = TransactionEntity(
        id = "t1",
        shopId = "shop",
        amount = amount,
        mode = "udhaar",
        items = items,
        occurredAt = 1_700_000_000_000L,
    )

    @Test
    fun `a quantity and unit are read back off the label`() {
        val row = LineItems.fromLabels(tx("chawal 2kg")).single()
        assertEquals("chawal", row.name)
        assertEquals("chawal", row.displayName)
        assertEquals(2.0, row.quantity!!, 1e-9)
        assertEquals("kg", row.unit)
    }

    @Test
    fun `a bare count has no unit`() {
        val row = LineItems.fromLabels(tx("kurti 3")).single()
        assertEquals("kurti", row.name)
        assertEquals(3.0, row.quantity!!, 1e-9)
        assertNull(row.unit)
    }

    @Test
    fun `a modifier stays on the display name but the noun is the key`() {
        // Grouping by "blue kurti" and "red kurti" separately would split one
        // product into two rows and hide it from the best-seller chart.
        val row = LineItems.fromLabels(tx("blue kurti 3")).single()
        assertEquals("kurti", row.name)
        assertEquals("blue kurti", row.displayName)
        assertEquals(3.0, row.quantity!!, 1e-9)
    }

    @Test
    fun `several items split on the comma`() {
        val rows = LineItems.fromLabels(tx("cheeni 1kg,chawal 2kg,doodh 2l"))
        assertEquals(listOf("cheeni", "chawal", "doodh"), rows.map { it.name })
        assertEquals(listOf("kg", "kg", "l"), rows.map { it.unit })
    }

    @Test
    fun `a label with no quantity still counts as a sale of that thing`() {
        val row = LineItems.fromLabels(tx("tamatar")).single()
        assertEquals("tamatar", row.name)
        assertNull(row.quantity)
    }

    @Test
    fun `no price is ever invented from the transaction total`() {
        // Dividing a bill to manufacture a rate is refused everywhere else in
        // the engine; the backfill must not be the exception.
        val rows = LineItems.fromLabels(tx("chawal 2kg,chai 1packet", amount = 250.0))
        assertTrue(rows.all { it.unitPrice == null && it.lineTotal == null })
    }

    @Test
    fun `an empty item string produces no rows`() {
        assertTrue(LineItems.fromLabels(tx("")).isEmpty())
    }

    @Test
    fun `structured lines from an extraction are preferred over labels`() {
        val extraction = Extraction(
            text = "",
            confidence = Confidence(overall = 1.0, route = "auto"),
            items = listOf("blue kurti 3"),
            lineItems = listOf(
                LineItem(
                    name = "kurti",
                    modifier = "blue",
                    displayName = "blue kurti",
                    quantity = 3.0,
                    unitPrice = 500.0,
                    lineTotal = 1500.0,
                    label = "blue kurti 3",
                ),
            ),
        )
        val row = LineItems.from(extraction, tx("blue kurti 3")).single()
        // The rate survives here, which it never could from a label.
        assertEquals(500.0, row.unitPrice!!, 1e-9)
        assertEquals(1500.0, row.lineTotal!!, 1e-9)
        assertEquals("kurti", row.name)
    }

    @Test
    fun `an extraction with no structure falls back to its labels`() {
        val extraction = Extraction(
            text = "",
            confidence = Confidence(overall = 1.0, route = "manual"),
            items = listOf("aloo 2kg"),
            lineItems = emptyList(),
        )
        val row = LineItems.from(extraction, tx("aloo 2kg")).single()
        assertEquals("aloo", row.name)
        assertEquals(2.0, row.quantity!!, 1e-9)
    }

    @Test
    fun `rows carry the parent transaction's shop and date for grouping`() {
        val parent = tx("chawal 2kg")
        val row = LineItems.fromLabels(parent).single()
        assertEquals(parent.id, row.transactionId)
        assertEquals(parent.shopId, row.shopId)
        assertEquals(parent.occurredAt, row.occurredAt)
    }
}
