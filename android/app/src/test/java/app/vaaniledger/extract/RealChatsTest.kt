package app.vaaniledger.extract

import app.vaaniledger.data.Catalogue
import app.vaaniledger.data.ShopCategory
import app.vaaniledger.extract.ConversationResolver.Turn
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chats read off a real phone (Gupta Store test group, 29-31 Oct), held to
 * the same answers as backend/tests/test_real_chats.py -- plus the shop's
 * price list, which only the phone applies.
 */
class RealChatsTest {

    private val t0 = LocalDateTime.of(2026, 10, 29, 23, 26)

    private fun chat(vararg rows: Triple<Long, String, Boolean>) = rows.mapIndexed { i, (m, body, shop) ->
        Turn(sender = if (shop) "You" else "Isha", body = body, timestamp = t0.plusMinutes(m), isShopkeeper = shop, contentHash = "h$i")
    }

    @After fun clearList() = Catalogue.install(emptyList())

    @Test
    fun `a group member quoting per kilo is a rate`() {
        val o = ConversationResolver.resolveAll(chat(
            Triple(0, "hello bhaiya", false), Triple(0, "2kg spring onions chaiye", false), Triple(1, "kitna hua?", false),
            Triple(3, "1kg ka 200rs", false), Triple(4, "okay i need 2kg", false),
            Triple(6, "hello bhaiya", false), Triple(7, "spring onions hai kya?", false), Triple(7, "ha", false),
            Triple(7, "1kg ka 200rs", false), Triple(8, "meko 2kg chaiye", false), Triple(8, "Ok", false),
        ), "Isha", ShopCategory.KIRANA).single()
        assertEquals(400.0, o.amount)
        val line = o.items.single()
        assertEquals(listOf<Any?>("spring", "onion", 2.0, "kg", 200.0), listOf(line.modifier, line.name, line.quantity, line.unit, line.unitPrice))
    }

    @Test
    fun `one for a thousand then two`() {
        val o = ConversationResolver.resolveAll(chat(
            Triple(0, "hello bhaiya", false), Triple(0, "yellow dupatta chaiye", false), Triple(0, "kitna hua", false),
            Triple(1, "1 for 1000rs", true), Triple(1, "i need 2 dupattas", false), Triple(1, "okay", true),
        ), "Isha", ShopCategory.APPAREL).single()
        assertEquals(listOf<Any?>(2000.0, "yellow", "dupatta", 2.0), listOf(o.amount, o.items[0].modifier, o.items[0].name, o.items[0].quantity))
    }

    @Test
    fun `price for one in a message`() {
        for ((text, amount, qty) in listOf(
            Triple("1 for 2000 kurta", 2000.0, null), Triple("2 kurta 1 ka 2000", 4000.0, 2.0),
            Triple("kurta 1 for 2000, 2 chahiye", 4000.0, 2.0), Triple("kurta 2000 ka ek, do chahiye", 4000.0, 2.0),
            Triple("dupatta 1000 each, 3 chaiye", 3000.0, 3.0), Triple("kurti 500 each", 500.0, null),
        )) {
            val e = OfflineExtractor.extract(text, category = ShopCategory.APPAREL)
            assertEquals(text, amount, e.amount)
            assertEquals(text, qty, e.lineItems.first().quantity)
        }
        assertEquals(85.0, OfflineExtractor.extract("crocin 650 ki 1 strip aur 1 vicks, 85", category = ShopCategory.PHARMACY).amount)
    }

    @Test
    fun `a lone items measure is found`() {
        for (text in listOf("spring onion chahiye, 2 kg", "spring onion bhej do 2kg", "spring onion - 2 kg",
            "spring onion\n2kg", "spring onion do kilo", "2kg chahiye spring onion")) {
            val li = OfflineExtractor.extract(text, category = ShopCategory.KIRANA).lineItems.single()
            assertEquals(text, 2.0 to "kg", li.quantity to li.unit)
        }
    }

    @Test
    fun `the price list multiplies by the kilos and converts grams`() {
        val list = Catalogue.parse("spring onion 200\naaloo 300\nkurta 2000")
        Catalogue.install(list)
        for ((text, cat, amount) in listOf(
            Triple("spring onion chahiye, 2 kg", ShopCategory.KIRANA, 400.0),
            Triple("2kg spring onion", ShopCategory.KIRANA, 400.0),
            Triple("spring onion do kilo", ShopCategory.KIRANA, 400.0),
            Triple("spring onion 1.5 kg", ShopCategory.KIRANA, 300.0),
            Triple("spring onion 250 gram", ShopCategory.KIRANA, 50.0),
            Triple("spring onion 500g", ShopCategory.KIRANA, 100.0),
            Triple("aadha kilo aaloo", ShopCategory.KIRANA, 150.0),
            Triple("aaloo 2", ShopCategory.KIRANA, 600.0),
            Triple("spring onion x2", ShopCategory.KIRANA, 400.0),
            Triple("spring onion", ShopCategory.KIRANA, 200.0),
            Triple("2 kurta", ShopCategory.APPAREL, 4000.0),
            Triple("do kurte", ShopCategory.APPAREL, 4000.0),
        )) {
            val e = CatalogueMatch.enrich(OfflineExtractor.extract(text, category = cat), list)
            assertEquals(text, amount, e.amount)
        }
        // A price the message states wins over the list.
        assertEquals(500.0, CatalogueMatch.enrich(OfflineExtractor.extract("2kg spring onion 500 rs", category = ShopCategory.KIRANA), list).amount)
    }
}
