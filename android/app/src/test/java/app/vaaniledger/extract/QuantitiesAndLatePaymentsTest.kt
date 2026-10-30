package app.vaaniledger.extract

import app.vaaniledger.data.ShopCategory
import app.vaaniledger.extract.ConversationResolver.Turn
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two field reports, held to the same answers as
 * `backend/tests/test_quantities_and_late_payments.py`.
 *
 * 1. "Shyam ne do kurti kharide do hazar mein" -- the count word counts the
 *    kurti; it is neither part of its name nor the bill.
 * 2. "Payment done" fifty minutes after an order booked on credit pays that
 *    order instead of standing as a separate entry.
 */
class QuantitiesAndLatePaymentsTest {

    private fun lines(text: String, category: ShopCategory = ShopCategory.APPAREL) =
        OfflineExtractor.extract(text, category = category).let { e ->
            e.amount to e.lineItems.map { it.name to it.quantity }
        }

    @Test
    fun `the reported sentence`() {
        assertEquals(2000.0 to listOf("kurti" to 2.0), lines("Shyam ne do kurti kharide do hazar mein."))
    }

    @Test
    fun `across hindi hinglish and digits`() {
        for (text in listOf(
            "Shyam ne do kurti kharide 2000 mein",
            "श्याम ने दो कुर्ती खरीदे दो हजार में",
            "do kurti do hazar",
            "2 kurti 2000",
            "Shyam ne 2 kurti kharide ₹2,000 mein",
        )) {
            val (amount, items) = lines(text)
            assertEquals(text, 2000.0, amount)
            assertEquals(text, 2.0, items.single().second)
        }
    }

    @Test
    fun `hindi plurals name the item`() {
        assertEquals(2000.0 to listOf("kurti" to 2.0), lines("shyam ne do kurtiyan kharidi do hazaar mein"))
        assertEquals(2.0, lines("श्याम ने दो कुर्तियाँ खरीदीं दो हजार में").second.single().second)
    }

    @Test
    fun `a count next to udhaar is not the bill`() {
        for (text in listOf("Shyam ne do kurti kharidi, do hazar udhaar", "do saree udhaar 3000", "2 kurti udhaar 3000")) {
            val (amount, items) = lines(text)
            assertTrue(text, (amount ?: 0.0) >= 2000.0)
            assertEquals(text, 2.0, items.single().second)
        }
    }

    @Test
    fun `the old adjacency cases still hold`() {
        assertEquals(250.0, OfflineExtractor.extract("dhai sau ka udhaar", category = ShopCategory.KIRANA).amount)
        assertEquals(500.0, OfflineExtractor.extract("500 ka udhaar", category = ShopCategory.KIRANA).amount)
        assertEquals(250.0, OfflineExtractor.extract("₹250", category = ShopCategory.KIRANA).amount)
    }

    @Test
    fun `media in the chat carries no numbers`() {
        for ((text, amount) in listOf(
            "IMG-20261030-WA0004.jpg 245 kB 2 kurti bhej do 500 each" to 1000.0,
            "PDF • 2 pages • 1.2 MB  bill.pdf  1500 ka udhaar" to 1500.0,
            "VID-20261030-WA0001.mp4 (file attached) 2 kurti 1000" to 1000.0,
        )) assertEquals(text, amount, lines(text).first)
        assertEquals(3.0, lines("<Media omitted> 3 saree chahiye").second.single().second)
        for (text in listOf("245 kB", "1.2 MB", "IMG-20261030-WA0004.jpg", "‎image omitted", "<Media omitted>")) {
            val (amount, items) = lines(text)
            assertEquals(text, null, amount)
            assertTrue(text, items.isEmpty())
        }
        assertTrue(NumberParser.normalise("64 GB pendrive 2 chahiye").contains("64"))
    }

    @Test
    fun `the imperative do is not added to a rate`() {
        for ((text, amount) in listOf(
            "2 kurti bhej do 500 each" to 1000.0, "2 kurti de do 500 each" to 1000.0,
            "3 saree bhej do 800 each" to 2400.0, "do kurti 500 each" to 1000.0,
            "2 kurti bhej do, 500 each" to 1000.0, "2 kurti 500 each, 3 saree 800 each" to 3400.0,
        )) assertEquals(text, amount, lines(text).first)
    }

    @Test
    fun `spring onions two kilo at 200 a kilo`() {
        val t = LocalDateTime.of(2026, 10, 30, 23, 32)
        fun turn(m: Long, body: String, shop: Boolean = false) =
            Turn(sender = if (shop) "You" else "Isha Agarwal", body = body, timestamp = t.plusMinutes(m), isShopkeeper = shop, contentHash = "s$m$body")
        val chat = listOf(
            turn(0, "hello bhaiya"), turn(1, "spring onions hai kya?"), turn(1, "ha", true),
            turn(1, "1kg ka 200rs", true), turn(2, "meko 2kg chaiye"), turn(2, "Ok", true),
        )
        for (category in listOf(ShopCategory.KIRANA, ShopCategory.GENERAL)) {
            val o = ConversationResolver.resolveAll(chat, "Isha Agarwal", category).single()
            assertEquals("sale" to 400.0, o.direction to o.amount)
            val line = o.items.single()
            assertEquals(listOf<Any?>("spring", "onion", 2.0, "kg", 200.0), listOf(line.modifier, line.name, line.quantity, line.unit, line.unitPrice))
        }
        for (text in listOf("200rs", "200rupaye", "₹200", "rs200", "200 rs", "200/-")) {
            assertEquals(text, 200.0, OfflineExtractor.extract(text, category = ShopCategory.KIRANA).amount)
        }
    }

    @Test
    fun `a typed leading count becomes the quantity`() {
        for ((typed, label) in listOf(
            "do kurti" to "kurti 2", "Do Kurti" to "Kurti 2", "2 kurti" to "kurti 2",
            "teen blue saree" to "blue saree 3", "2 kg chawal" to "chawal 2kg", "दो कुर्ती" to "कुर्ती 2",
        )) assertEquals(typed, label, RulesEngine.normaliseLabel(typed))
        for (typed in listOf("kurti 2", "chawal 2kg", "kurti", "5 star", "dhai sau", "do")) {
            assertEquals(typed, typed, RulesEngine.normaliseLabel(typed))
        }
    }

    // ---- late payments ----------------------------------------------------

    private val t0: LocalDateTime = LocalDateTime.of(2026, 10, 30, 10, 0)

    private fun at(minute: Long, body: String, shop: Boolean = false) = Turn(
        sender = if (shop) "Gupta Store" else "Rahul Verma",
        body = body,
        timestamp = t0.plusMinutes(minute),
        isShopkeeper = shop,
        contentHash = "h$minute",
    )

    private val order = listOf(at(0, "2 kurti chahiye"), at(1, "2000 hua total", shop = true), at(2, "udhaar likh lo"))

    private fun after(minutes: Long, body: String, payLinks: List<LocalDateTime?> = emptyList()) =
        ConversationResolver.resolveAll(order + at(minutes, body), "Rahul Verma", ShopCategory.APPAREL, payLinkSent = payLinks)

    @Test
    fun `a late payment pays the order`() {
        for ((wording, mode) in listOf(
            "Payment done." to "cash", "UPI kar diya." to "upi", "Cash de diya." to "cash",
            "Paise bhej diye." to "upi", "पैसे भेज दिए" to "upi", "maine payment kar di" to "cash",
            "2000 bhej diye" to "upi",
        )) {
            val orders = after(50, wording)
            assertEquals(wording, 1, orders.size)
            assertEquals(wording, Triple("sale", 2000.0, mode), Triple(orders[0].direction, orders[0].amount, orders[0].mode))
            assertTrue(wording, "paid-later" in orders[0].signals)
        }
    }

    @Test
    fun `after a pay link reminder it was paid by upi`() {
        assertEquals("upi", after(50, "Payment done.", listOf(t0.plusMinutes(30))).single().mode)
    }

    @Test
    fun `a part payment stays separate`() {
        val orders = after(50, "500 bhej diye")
        assertEquals(listOf("sale" to 2000.0, "payment" to 500.0), orders.map { it.direction to it.amount })
        assertEquals("udhaar", orders[0].mode)
    }

    @Test
    fun `a payment weeks later is not folded`() {
        assertEquals(listOf("sale", "payment"), after(60L * 24 * 10, "Payment done.").map { it.direction })
    }

    @Test
    fun `an order paid on the spot is not paid twice`() {
        val orders = ConversationResolver.resolveAll(
            listOf(at(0, "2 kurti chahiye"), at(1, "2000 hua total", shop = true), at(2, "gpay kar diya"), at(60, "payment done")),
            "Rahul Verma", ShopCategory.APPAREL,
        )
        assertEquals(listOf("sale" to "upi", "payment" to "unknown"), orders.map { it.direction to it.mode })
    }
}
