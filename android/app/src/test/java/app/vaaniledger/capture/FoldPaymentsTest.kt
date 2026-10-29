package app.vaaniledger.capture

import app.vaaniledger.net.Confidence
import app.vaaniledger.net.Extraction
import org.junit.Assert.assertEquals
import org.junit.Test

class FoldPaymentsTest {
    private fun e(direction: String, amount: Double?, mode: String) =
        Extraction(text = "", amount = amount, mode = mode, direction = direction, confidence = Confidence(0.5, "ask"))

    /** The ₹5,000 order and "i have completed the payment" on one screen were two cards. */
    @Test
    fun `a figureless payment after an order pays that order`() {
        val folded = ReviewQueue.foldLonePayments(listOf(e("sale", 5000.0, "upi"), e("payment", null, "unknown")))
        assertEquals(1, folded.size)
        assertEquals("upi", folded[0].mode)
        val credit = ReviewQueue.foldLonePayments(listOf(e("sale", 500.0, "udhaar"), e("payment", null, "unknown")))
        assertEquals("cash", credit.single().mode)
    }

    @Test
    fun `a payment on its own stays a payment`() {
        assertEquals(1, ReviewQueue.foldLonePayments(listOf(e("payment", null, "unknown"))).size)
        assertEquals(2, ReviewQueue.foldLonePayments(listOf(e("sale", 500.0, "udhaar"), e("payment", 200.0, "cash"))).size)
    }
}
