package app.vaaniledger.extract

import app.vaaniledger.data.ShopCategory
import app.vaaniledger.extract.ConversationResolver.Turn
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device conversation resolver and export parser, held to the
 * server's answers.
 *
 * Every expected value here was produced by running the SAME input through
 * `backend/app/extraction/conversation.py` and `whatsapp.py`. If a case
 * fails, one of the two engines has drifted, and the phone and the dashboard
 * now disagree about someone's money -- which the README is explicit is the
 * thing that makes a shopkeeper stop trusting both.
 */
class ConversationParityTest {

    private val t0: LocalDateTime = LocalDateTime.of(2026, 9, 10, 9, 10)

    private fun turns(vararg rows: Pair<String, Boolean>): List<Turn> =
        rows.mapIndexed { i, (body, shop) ->
            Turn(
                sender = if (shop) "You" else "Isha",
                body = body,
                timestamp = t0.plusMinutes(i.toLong()),
                isShopkeeper = shop,
                contentHash = "h$i",
            )
        }

    private fun order(category: ShopCategory, vararg rows: Pair<String, Boolean>) =
        ConversationResolver.resolveAll(turns(*rows), "Isha", category).singleOrNull()

    // ---- the four shapes seen in real chats -------------------------------

    @Test
    fun `a price against one unit is a rate - 500 for 1kg x 2 = 1000`() {
        val o = order(
            ShopCategory.KIRANA,
            "hello sir" to false, "i need aloo" to false, "2kg" to false,
            "what is the price?" to false,
            "500 for 1kg" to true,
            "i need 2 kilos" to false,
        )
        assertNotNull(o)
        assertEquals("aloo", o!!.items[0].name)
        assertEquals(2.0, o.items[0].quantity)
        assertEquals("kg", o.items[0].unit)
        assertEquals(500.0, o.items[0].unitPrice)
        assertEquals(1000.0, o.amount)
        assertTrue(o.signals.contains("shop-quoted-rate"))
    }

    @Test
    fun `a bundle quote is a total - 2kg for 500 = 500`() {
        val o = order(
            ShopCategory.KIRANA,
            "2kg tamatar chaiye" to false,
            "2kg for" to true, "500 only" to true, "organic grown tamatar" to true,
            "haan bhej do" to false,
        )
        assertNotNull(o)
        assertEquals("tamatar", o!!.items[0].name)
        assertEquals(2.0, o.items[0].quantity)
        assertNull(o.items[0].unitPrice)
        assertEquals(500.0, o.amount)
        assertTrue(o.signals.contains("shop-quoted-total"))
    }

    @Test
    fun `a bare quote is per unit - 500 ka x 3 kurtis = 1500`() {
        val o = order(
            ShopCategory.APPAREL,
            "is there an availability of blue kurtis" to false,
            "yes, 500 ka" to true,
            "mujhe 3 kharidne hai" to false,
        )
        assertNotNull(o)
        assertEquals("kurti", o!!.items[0].name)
        assertEquals("blue", o.items[0].modifier)
        assertEquals(3.0, o.items[0].quantity)
        assertEquals(1500.0, o.amount)
        assertTrue(o.carriedTopic)
    }

    @Test
    fun `an explicit each is a rate - 1200 each x 2 = 2400`() {
        val o = order(
            ShopCategory.APPAREL,
            "red saree hai kya" to false, "haan, 1200 each" to true, "i want 2 pieces" to false,
        )
        assertNotNull(o)
        assertEquals(2.0, o!!.items[0].quantity)
        assertEquals("pc", o.items[0].unit)
        assertEquals(2400.0, o.amount)
    }

    // ---- nothing is invented -------------------------------------------

    @Test
    fun `an enquiry alone books nothing`() {
        assertNull(order(ShopCategory.APPAREL, "is blue kurti available" to false, "yes, 500 ka" to true))
    }

    @Test
    fun `the shopkeeper cannot place an order`() {
        assertNull(order(ShopCategory.APPAREL, "blue kurti 500 ka hai" to true, "bhej do" to true))
    }

    @Test
    fun `ordering off a bundle for a different quantity is flagged not divided`() {
        val o = order(
            ShopCategory.KIRANA,
            "tamatar chaiye" to false, "2kg for" to true, "500 only" to true, "5 kilo chahiye" to false,
        )
        assertNotNull(o)
        assertTrue(o!!.signals.contains("bundle-quantity-mismatch"))
    }

    @Test
    fun `silence splits threads so a stale topic cannot reach a later order`() {
        val rows = turns("blue kurti hai kya" to false) +
            listOf(Turn("Isha", "3 chahiye", t0.plusDays(4), false, "later"))
        assertTrue(ConversationResolver.resolveAll(rows, "Isha", ShopCategory.APPAREL).isEmpty())
    }

    // ---- the real export, byte for byte -------------------------------------

    private val export = """
        03/09/2026, 23:21 - Messages and calls are end-to-end encrypted. Only people in this chat can read, listen to, or share them. *Learn more*
        03/09/2026, 23:21 - Isha created group "test capstone"
        03/09/2026, 23:21 - Isha added you
        03/09/2026, 23:21 - Isha: hello bhaiya aapke paas kurti hai?
        03/09/2026, 23:22 - Isha: i want 5 blue kurtis
        03/09/2026, 23:22 - Isha: what is price?
        10/09/2026, 08:33 - Aadit: <Media omitted>
        10/09/2026, 09:10 - Isha: hello sir
        10/09/2026, 09:10 - Isha: i need aloo
        10/09/2026, 09:10 - Isha: 2kg
        10/09/2026, 09:10 - Isha: what is the price?
        10/09/2026, 09:10 - Aadit: 500 for 1kg
        10/09/2026, 09:10 - Isha: i need 2 kilos
        10/09/2026, 09:10 - Aadit: Okay
        17/09/2026, 10:25 - Aadit: Aalo is 500 for 1kg
    """.trimIndent()

    @Test
    fun `the real export parses like the server does`() {
        val msgs = ExportParser.parse(export)
        // Python: 11 messages. System lines and <Media omitted> are dropped.
        assertEquals(11, msgs.size)
        assertEquals(setOf("Aadit", "Isha"), msgs.map { it.sender }.toSet())
        assertEquals("Isha", ExportParser.inferCustomer(msgs, setOf("Sharma Kirana Store")))
    }

    @Test
    fun `content hashes are identical to the server's`() {
        // Produced by backend/app/extraction/whatsapp.py on the same text. If
        // these ever differ, an order approved on the phone is re-offered by
        // the server, or vice versa, because the watermarks cannot see each
        // other.
        val expected = mapOf(
            "i need aloo" to "7d5851aa466987b4d23eaed108d22522f385d6c2",
            "2kg" to "14f0353cb82ddc92c86b883a3a1abbf80f0a7ead",
            "500 for 1kg" to "4409d68d190f42cb91ad6b0eaf045f1118966ba9",
            "i need 2 kilos" to "fa570c33507c90e99a25924f997add523cdee68d",
        )
        val byBody = ExportParser.parse(export).associate { it.body to it.contentHash }
        for ((body, hash) in expected) {
            assertEquals("hash of \"$body\"", hash, byBody[body])
        }
    }

    @Test
    fun `the real export resolves the aloo order at 1000`() {
        val msgs = ExportParser.parse(export)
        val customer = ExportParser.inferCustomer(msgs, setOf("Sharma Kirana Store"))!!
        val turns = ExportParser.toTurns(msgs, customer, setOf("Sharma Kirana Store"))
        val orders = ConversationResolver.resolveAll(turns, customer, ShopCategory.KIRANA)

        // Python: threads=3 orders=1. The kurti thread has no shop reply and
        // "kurti" is not kirana; the final "Aalo is 500 for 1kg" is a quote
        // with no commitment after it.
        assertEquals(1, orders.size)
        val o = orders[0]
        assertEquals("Isha", o.customer)
        assertEquals("aloo", o.items[0].name)
        assertEquals(2.0, o.items[0].quantity)
        assertEquals("kg", o.items[0].unit)
        assertEquals(500.0, o.items[0].unitPrice)
        assertEquals(1000.0, o.amount)
        assertEquals(LocalDateTime.of(2026, 9, 10, 9, 10), o.occurredAt)
        assertTrue(o.evidence.contains("Aadit: 500 for 1kg"))
        assertTrue(o.evidence.contains("Isha: i need 2 kilos"))
    }

    @Test
    fun `an unseen spelling of aloo is recognised`() {
        assertEquals("aalo", RulesEngine.applyRules("Aalo is 500 for 1kg", ShopCategory.KIRANA).items[0].name)
    }
}
