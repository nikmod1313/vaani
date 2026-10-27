package app.vaaniledger.extract

import app.vaaniledger.data.ShopCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity tests for the on-device engine.
 *
 * Every case here is lifted from the Python suite that guards
 * `backend/app/extraction/`. The point is not to re-test the algorithms --
 * Python already does that with 214 cases -- but to prove the KOTLIN PORT
 * agrees with them, because a port that quietly disagrees about a number is
 * worse than no port at all: it would put a different figure in the khata
 * depending on whether the phone happened to have signal.
 *
 * The traps below are the ones the Python source documents by name. Each was
 * a real bug that would have written a wrong number into someone's ledger.
 */
class OfflineParityTest {

    private fun amountOf(text: String, category: ShopCategory = ShopCategory.GENERAL) =
        RulesEngine.applyRules(text, category).amount

    // ----------------------------------------------------------------------
    // Number words
    // ----------------------------------------------------------------------

    @Test
    fun `hindi number words parse`() {
        assertEquals(250.0, NumberParser.parseNumber("dhai sau")!!, 0.001)
        assertEquals(150.0, NumberParser.parseNumber("dedh sau")!!, 0.001)
        assertEquals(125.0, NumberParser.parseNumber("sava sau")!!, 0.001)
        assertEquals(175.0, NumberParser.parseNumber("paune do sau")!!, 0.001)
        assertEquals(350.0, NumberParser.parseNumber("sadhe teen sau")!!, 0.001)
        assertEquals(2500.0, NumberParser.parseNumber("do hazaar paanch sau")!!, 0.001)
    }

    @Test
    fun `devanagari digits and words parse`() {
        assertEquals(250.0, NumberParser.parseNumber("२५०")!!, 0.001)
        assertEquals(100.0, NumberParser.parseNumber("सौ")!!, 0.001)
    }

    /**
     * "do" means 2 but usually "send": "chai bhej do, dhai sau" must be 250,
     * not (2 + 2.5) * 100 = 450.
     */
    @Test
    fun `do ambiguity - imperative is not the number two`() {
        assertEquals(250.0, amountOf("chai bhej do, dhai sau")!!, 0.001)
    }

    @Test
    fun `do followed by a unit really is two`() {
        val items = RulesEngine.extractItems("do kilo chawal", ShopCategory.KIRANA)
        assertEquals(1, items.size)
        assertEquals(2.0, items[0].quantity!!, 0.001)
    }

    // ----------------------------------------------------------------------
    // A quantity is not a price
    // ----------------------------------------------------------------------

    /** "2 kilo chawal bhej do" booked Rs 2 before this was fixed. */
    @Test
    fun `quantity alone yields no amount`() {
        assertNull(amountOf("2 kilo chawal bhej do", ShopCategory.KIRANA))
    }

    @Test
    fun `a measure is never the amount`() {
        assertNull(amountOf("chai khatam, 2 packet bhej do jaldi", ShopCategory.KIRANA))
    }

    @Test
    fun `ka marks the number as a price`() {
        assertEquals(250.0, amountOf("2 kilo chawal, dhai sau ka udhaar", ShopCategory.KIRANA)!!, 0.001)
    }

    // ----------------------------------------------------------------------
    // Stated rates
    // ----------------------------------------------------------------------

    @Test
    fun `rate times quantity is the amount`() {
        val result = RulesEngine.applyRules("3 kurti 500 each", ShopCategory.APPAREL)
        assertEquals(1500.0, result.amount!!, 0.001)
        assertEquals("line-totals", result.amountSource)
    }

    /** "each" is unambiguous even when a mode word follows it. */
    @Test
    fun `each survives a following mode word`() {
        assertEquals(
            1500.0,
            amountOf("3 kurti 500 each udhaar", ShopCategory.APPAREL)!!,
            0.001,
        )
    }

    /** A total with a quantity must not become a per-unit price. */
    @Test
    fun `a rate is never inferred by division`() {
        val items = RulesEngine.applyRules("3 kurti 1500 ka udhaar", ShopCategory.APPAREL).items
        assertNull(items[0].unitPrice)
    }

    @Test
    fun `a rate does not leak onto the next item`() {
        val items = RulesEngine.applyRules("2 saree aur 3 kurti 500 each", ShopCategory.APPAREL).items
        assertEquals(2, items.size)
        assertNull(items[0].unitPrice)
        assertEquals(500.0, items[1].unitPrice!!, 0.001)
    }

    // ----------------------------------------------------------------------
    // Categories and plurals
    // ----------------------------------------------------------------------

    @Test
    fun `apparel knows clothes and kirana does not`() {
        assertTrue(RulesEngine.extractItems("3 kurti chahiye", ShopCategory.KIRANA).isEmpty())
        assertEquals(1, RulesEngine.extractItems("3 kurti chahiye", ShopCategory.APPAREL).size)
    }

    /** GENERAL must widen the vocabulary, never erase it. */
    @Test
    fun `general is an open vocabulary`() {
        assertEquals(1, RulesEngine.extractItems("2 kilo chawal", ShopCategory.GENERAL).size)
        assertEquals(1, RulesEngine.extractItems("3 kurti", ShopCategory.GENERAL).size)
    }

    /**
     * The regression that put "kurti 3" in a real khata instead of
     * "blue kurti 3", because the shop had not picked a category yet.
     */
    @Test
    fun `general keeps modifiers too`() {
        assertEquals(
            "blue kurti 3",
            RulesEngine.extractItems("3 blue kurti", ShopCategory.GENERAL)[0].label(),
        )
    }

    @Test
    fun `english plurals resolve to the singular`() {
        assertEquals(
            "kurti",
            RulesEngine.extractItems("2 kurtis chahiye", ShopCategory.APPAREL)[0].name,
        )
    }

    /** "jeans" is already the word; "jean" is not. */
    @Test
    fun `a lexicon plural is not stripped`() {
        assertEquals(
            "jeans",
            RulesEngine.extractItems("2 jeans chahiye", ShopCategory.APPAREL)[0].name,
        )
    }

    // ----------------------------------------------------------------------
    // Verb traps
    // ----------------------------------------------------------------------

    /** "khaate mein daal do" is not two kilos of lentils. */
    @Test
    fun `weak item words need corroboration`() {
        assertTrue(RulesEngine.extractItems("khaate mein daal do", ShopCategory.KIRANA).isEmpty())
    }

    @Test
    fun `dal with a quantity is lentils`() {
        val items = RulesEngine.extractItems("dal 2 kilo", ShopCategory.KIRANA)
        assertEquals(1, items.size)
        assertEquals(2.0, items[0].quantity!!, 0.001)
    }

    // ----------------------------------------------------------------------
    // Mode and direction
    // ----------------------------------------------------------------------

    @Test
    fun `payment modes are detected`() {
        assertEquals(RulesEngine.PaymentMode.UDHAAR, RulesEngine.detectMode("udhaar likh lena").first)
        assertEquals(RulesEngine.PaymentMode.CASH, RulesEngine.detectMode("nakad diya").first)
        assertEquals(RulesEngine.PaymentMode.UPI, RulesEngine.detectMode("phonepe kar diya").first)
    }

    /** "likh lena" means credit even without the word udhaar. */
    @Test
    fun `write-it-down phrases mean credit`() {
        assertEquals(RulesEngine.PaymentMode.UDHAAR, RulesEngine.detectMode("likh lena").first)
    }

    /** Past-tense giving is money coming back, not a sale. */
    @Test
    fun `past tense payment is a payment`() {
        assertEquals(RulesEngine.Direction.PAYMENT, RulesEngine.detectDirection("paise bhej diya"))
    }

    /**
     * You cannot pay WITH udhaar: on a payment the instrument wins over the
     * debt type being settled.
     */
    @Test
    fun `instrument wins over debt type on a payment`() {
        assertEquals(
            RulesEngine.PaymentMode.UPI,
            RulesEngine.detectMode("wo udhaar clear kar diya, gpay se bheja").first,
        )
    }

    // ----------------------------------------------------------------------
    // The full offline pipeline
    // ----------------------------------------------------------------------

    @Test
    fun `end to end offline extraction fills the fields`() {
        val result = OfflineExtractor.extract(
            text = "2 kilo chawal aur 1 packet chai, dhai sau ka udhaar",
            knownCustomers = listOf("Rahul Verma"),
            senderName = "Rahul Verma",
            category = ShopCategory.KIRANA,
        )
        assertEquals(250.0, result.amount!!, 0.001)
        assertEquals("udhaar", result.mode)
        assertEquals("sale", result.direction)
        assertEquals(2, result.items.size)
        assertEquals("rules-offline", result.source)
        assertTrue(result.confidence.overall > 0.5)
    }

    @Test
    fun `offline extraction resolves a stated rate`() {
        val result = OfflineExtractor.extract(
            text = "5 blue kurti 500 each, cash",
            senderName = "Isha",
            category = ShopCategory.APPAREL,
        )
        assertEquals(2500.0, result.amount!!, 0.001)
        assertEquals("cash", result.mode)
        assertEquals("blue kurti 5", result.items[0])
        assertEquals(500.0, result.lineItems[0].unitPrice!!, 0.001)
    }

    /** An unknown sender is flagged as new rather than silently merged. */
    @Test
    fun `an unknown sender is a new customer`() {
        val result = OfflineExtractor.extract(
            text = "2 kilo chawal",
            knownCustomers = listOf("Rahul Verma"),
            senderName = "Isha",
            category = ShopCategory.KIRANA,
        )
        assertEquals("Isha", result.customer)
        assertTrue(result.customerIsNew)
    }

    @Test
    fun `a known sender matches case insensitively`() {
        val result = OfflineExtractor.extract(
            text = "2 kilo chawal",
            knownCustomers = listOf("Rahul Verma"),
            senderName = "rahul verma",
            category = ShopCategory.KIRANA,
        )
        assertEquals("Rahul Verma", result.customer)
        assertTrue(!result.customerIsNew)
    }

    // ----------------------------------------------------------------------
    // A measure must survive a price that follows it.
    //
    // From a real chat, sent one word per message:
    //     "2kg for"  "500 only"  "organic"  "grown"  "tamatar"
    // The 500 overwrote the 2 and kept its "kg", booking FIVE HUNDRED KILOS
    // of tomatoes. Pinned in both engines so they cannot drift apart on it.
    // ----------------------------------------------------------------------

    @Test
    fun `bare number does not overwrite a measured quantity`() {
        val result = RulesEngine.applyRules(
            "2kg for 500 only organic grown tamatar", ShopCategory.KIRANA,
        )
        assertEquals(2.0, result.items[0].quantity!!, 0.001)
        assertEquals("kg", result.items[0].unit)
        assertEquals(500.0, result.amount!!, 0.001)
    }

    @Test
    fun `a bare number carries no borrowed unit`() {
        val result = RulesEngine.applyRules("5 kg 200 rupaye aloo", ShopCategory.KIRANA)
        assertEquals(5.0, result.items[0].quantity!!, 0.001)
        assertEquals("kg", result.items[0].unit)
        assertEquals(200.0, result.amount!!, 0.001)
    }

    @Test
    fun `a second real measure still replaces the first`() {
        val items = RulesEngine.applyRules(
            "2 kilo chawal aur 5 kilo atta", ShopCategory.KIRANA,
        ).items
        assertEquals(2.0, items[0].quantity!!, 0.001)
        assertEquals(5.0, items[1].quantity!!, 0.001)
    }
    // ----------------------------------------------------------------------
    // Detection fixes found by backend/eval/chats.py -- mirrors test_detection.py
    // ----------------------------------------------------------------------

    /** "total 1,250 hua" was read as Rs 250. */
    @Test
    fun `digit group commas are part of the number`() {
        assertEquals(1250.0, NumberParser.findNumbers("total 1,250 hua")[0].value, 0.001)
        assertEquals(125000.0, NumberParser.findNumbers("1,25,000 ho gaya")[0].value, 0.001)
        assertEquals(listOf(2.0, 3.0), NumberParser.findNumbers("2,3 kurti").map { it.value })
    }

    /** "2 packet parle g, 1 kurkure" booked Rs 1. */
    @Test
    fun `a count is never money`() {
        assertNull(amountOf("2 packet parle g, 1 kurkure", ShopCategory.KIRANA))
        assertNull(amountOf("do kele aur ek kilo seb", ShopCategory.KIRANA))
    }

    /** "3 sabun 120 ka UPI kar diya" was booked as a debt being settled. */
    @Test
    fun `goods paid for on the spot are a sale`() {
        val r = RulesEngine.applyRules("3 sabun 120 ka UPI kar diya", ShopCategory.KIRANA)
        assertEquals(RulesEngine.Direction.SALE, r.direction)
        assertEquals(120.0, r.amount!!, 0.001)
    }

    /** "bheja tha" about bad flour invented a payment. */
    @Test
    fun `a complaint about goods is not a payment`() {
        val r = RulesEngine.applyRules("kal 3 kg aata bheja tha wo kharab tha", ShopCategory.KIRANA)
        assertTrue(r.direction != RulesEngine.Direction.PAYMENT)
    }

    @Test
    fun `everyday grocery and hindi plurals are known`() {
        val names = RulesEngine.extractItems(
            "1 kg besan, 500 gram sooji, ek darjan ande, paav kilo adrak", ShopCategory.KIRANA,
        ).map { it.name }.toSet()
        assertEquals(setOf("besan", "sooji", "anda", "adrak"), names)
    }

    /** "toor dal" and "moong dal" were both "dal". */
    @Test
    fun `varieties stay separate lines`() {
        val labels = RulesEngine.extractItems("toor dal 2 kg moong dal 1 kg", ShopCategory.KIRANA)
            .map { it.displayName }
        assertEquals(listOf("toor dal", "moong dal"), labels)
        assertEquals("chana", RulesEngine.extractItems("2 kg chana", ShopCategory.KIRANA)[0].name)
    }

    /** A captured "Save (987) 650-0001?" banner was booked as a Rs 987 bill. */
    @Test
    fun `a phone number is never money`() {
        assertNull(amountOf("Save (987) 650-0001? bhaiya 1 kg besan aur 2 packet maggi bhej dena", ShopCategory.KIRANA))
        assertNull(amountOf("is number pe call karna 9876543210, 2 kg chawal", ShopCategory.KIRANA))
        assertEquals(500.0, amountOf("+91 98765 43210 pe bhejo 500 ka saman", ShopCategory.KIRANA)!!, 0.001)
    }

    @Test
    fun `a medicine strength is part of its name`() {
        val item = RulesEngine.extractItems("dolo 650 ki 2 strip", ShopCategory.PHARMACY)[0]
        assertEquals("dolo 650", item.name)
        assertEquals(2.0, item.quantity!!, 0.001)
        assertEquals("strip", item.unit)
    }
}
