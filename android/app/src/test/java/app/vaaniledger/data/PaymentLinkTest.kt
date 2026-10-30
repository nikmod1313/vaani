package app.vaaniledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which order already on credit a later payment pays. */
class PaymentLinkTest {

    private var clock = 1_700_000_000_000L

    private fun sale(id: String, amount: Double, mode: String = "udhaar") =
        TransactionEntity(id = id, shopId = "s", amount = amount, mode = mode, direction = "sale", occurredAt = clock++)

    private fun payment(amount: Double) =
        TransactionEntity(id = "p$clock", shopId = "s", amount = amount, mode = "cash", direction = "payment", occurredAt = clock++)

    @Test
    fun `a payment of the order's figure pays that order`() {
        val rows = listOf(sale("a", 500.0), sale("b", 2000.0))
        assertEquals(listOf("b"), PaymentLink.creditsPaidBy(rows, 2000.0).map { it.id })
    }

    @Test
    fun `no figure pays the latest order still on credit`() {
        val rows = listOf(sale("a", 500.0), sale("b", 2000.0))
        assertEquals(listOf("b"), PaymentLink.creditsPaidBy(rows, null).map { it.id })
    }

    @Test
    fun `the whole balance pays every open order`() {
        val rows = listOf(sale("a", 500.0), sale("b", 2000.0))
        assertEquals(listOf("a", "b"), PaymentLink.creditsPaidBy(rows, 2500.0).map { it.id })
    }

    @Test
    fun `a part payment pays nothing outright`() {
        assertTrue(PaymentLink.creditsPaidBy(listOf(sale("a", 2000.0)), 500.0).isEmpty())
    }

    @Test
    fun `an order already partly paid is not flipped`() {
        val rows = listOf(sale("a", 2000.0), payment(500.0))
        assertTrue(PaymentLink.creditsPaidBy(rows, 1500.0).isEmpty())
        assertTrue(PaymentLink.creditsPaidBy(rows, null).isEmpty())
    }

    @Test
    fun `cash and upi sales are never touched`() {
        assertTrue(PaymentLink.creditsPaidBy(listOf(sale("a", 2000.0, "upi")), 2000.0).isEmpty())
    }
}
