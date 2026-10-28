package app.vaaniledger.extract

import app.vaaniledger.data.Catalogue
import app.vaaniledger.data.CatalogueItem
import app.vaaniledger.data.ShopCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shop's own price list: names the vocabulary lacks, prices nobody typed. */
class CatalogueMatchTest {

    private val list = listOf(
        CatalogueItem("muslin kurti", 450.0, "piece"),
        CatalogueItem("chanderi dupatta", 300.0),
        CatalogueItem("Dolo 650", 30.0),
    )

    @After fun forget() = Catalogue.install(emptyList())

    private fun read(text: String, category: ShopCategory = ShopCategory.APPAREL) =
        CatalogueMatch.enrich(OfflineExtractor.extract(text = text, category = category), list)

    @Test
    fun `a list name is read whole and priced`() {
        Catalogue.install(list)
        val e = read("2 muslin kurti bhej dena")
        assertEquals(listOf("muslin kurti"), e.lineItems.map { it.displayName })
        assertEquals(2.0, e.lineItems[0].quantity!!, 0.0)
        assertEquals(900.0, e.amount!!, 0.0)
        assertTrue(CatalogueMatch.SIGNAL in e.signals)
        assertEquals("ask", e.confidence.route)   // a priced guess is confirmed, never auto-booked
    }

    @Test
    fun `a stated total beats the list`() {
        Catalogue.install(list)
        val e = read("2 muslin kurti, 800 ka udhaar")
        assertEquals(800.0, e.amount!!, 0.0)
    }

    @Test
    fun `a typo and a plural still find the item`() {
        Catalogue.install(list)
        val e = read("ek chanderi dupattas aur 1 muslim kurti")
        assertEquals(setOf("muslin kurti", "chanderi dupatta"), e.lineItems.map { it.displayName }.toSet())
        assertEquals(750.0, e.amount!!, 0.0)
    }

    @Test
    fun `a word the engine never knew is found from the list`() {
        val e = CatalogueMatch.enrich(
            OfflineExtractor.extract(text = "3 dolo 650 de do", category = ShopCategory.KIRANA),
            list,
        )
        assertTrue(e.lineItems.any { it.displayName == "Dolo 650" })
    }

    @Test
    fun `without a list nothing changes`() {
        val plain = OfflineExtractor.extract(text = "2 kurti bhej do", category = ShopCategory.APPAREL)
        assertEquals(plain, CatalogueMatch.enrich(plain, emptyList()))
        assertNull(plain.amount)
    }

    @Test
    fun `the list is written the way a shopkeeper writes`() {
        val parsed = Catalogue.parse("muslin kurti 450\nSugar - ₹45/kg\nDolo 650, 30\n\njust a name")
        assertEquals(CatalogueItem("muslin kurti", 450.0, null), parsed[0])
        assertEquals(CatalogueItem("Sugar", 45.0, "kg"), parsed[1])
        assertEquals(CatalogueItem("Dolo 650", 30.0, null), parsed[2])
        assertEquals(CatalogueItem("just a name"), parsed[3])
        assertEquals(parsed, Catalogue.parse(Catalogue.format(parsed)))
    }
}
