package app.vaaniledger.extract

import app.vaaniledger.data.ShopCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two voice entries from the phone that came back with no customer. */
class VoiceNamesTest {
    @Test
    fun `the customer is taken from the spoken sentence`() {
        assertEquals("निरखर्भ", VoiceNames.customerIn("निरखर्भ ने मुझे ₹2000 के दो जूते खरीदे"))
        assertEquals("Isha", VoiceNames.customerIn("Isha has bought blue kurtis each at 500 rupees"))
        assertEquals("Rahul", VoiceNames.customerIn("rahul ko 2 kilo aloo diya"))
        assertNull(VoiceNames.customerIn("मैंने 500 रुपये दिए"))
        assertNull(VoiceNames.customerIn("2 kilo aloo 80 udhaar"))
    }

    @Test
    fun `shoes are clothing-shop items`() {
        val e = OfflineExtractor.extract(text = "निरखर्भ ने मुझे ₹2000 के दो जूते खरीदे", senderName = "निरखर्भ", category = ShopCategory.APPAREL)
        assertEquals(2000.0, e.amount!!, 0.0)
        assertTrue(e.items.joinToString(), e.lineItems.any { it.name == "जूते" || it.name == "जूता" })
    }
}
