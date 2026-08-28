package app.vaaniledger

import app.vaaniledger.reminder.ReminderLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the parts of ReminderLinks that are pure logic.
 *
 * `reminderIntent` needs a real Intent so it stays out of here, but phone
 * normalisation and URL encoding are exactly where a silent mistake sends a
 * reminder to nobody — and that failure looks identical to success, because
 * WhatsApp simply opens on a blank chat.
 */
class ReminderLinksTest {

    @Test
    fun `ten digit number gets the country code`() {
        assertEquals("919876543210", ReminderLinks.normalisePhone("9876543210"))
    }

    @Test
    fun `formatting a contact is written however people save it`() {
        // All four are the same number, saved four different ways.
        listOf("+91 98765 43210", "+919876543210", "098765 43210", "9876543210")
            .forEach { assertEquals("919876543210", ReminderLinks.normalisePhone(it)) }
    }

    @Test
    fun `international dialling prefix is stripped`() {
        assertEquals("919876543210", ReminderLinks.normalisePhone("00919876543210"))
    }

    @Test
    fun `a number too short to be real is refused, not guessed`() {
        assertNull(ReminderLinks.normalisePhone("12345"))
        assertNull(ReminderLinks.normalisePhone(""))
        assertNull(ReminderLinks.normalisePhone(null))
    }

    @Test
    fun `spaces encode as percent-20, never as plus`() {
        // URLEncoder emits "+" for a space, which wa.me renders LITERALLY as
        // a plus sign in the message box.
        val link = ReminderLinks.whatsAppLink("9876543210", "do sau ka udhaar")
        assertTrue("no raw + in the text", !link.substringAfter("text=").contains("+"))
        assertTrue(link.contains("%20"))
    }

    @Test
    fun `an unusable number still yields a share link`() {
        // No recipient is better than a broken chat: fall through to the
        // share sheet rather than opening wa.me on a bad number.
        val link = ReminderLinks.whatsAppLink(null, "hello")
        assertTrue(link.startsWith("https://wa.me/?text="))
    }

    @Test
    fun `the message names the shop, the amount and the date`() {
        val msg = ReminderLinks.composeMessage("Rahul Verma", "Sharma Kirana", 250.0)
        assertTrue(msg.contains("Rahul Verma"))
        assertTrue(msg.contains("Sharma Kirana"))
        assertTrue(msg.contains("250"))
    }

    @Test
    fun `upi link fixes two decimals and INR`() {
        // Several UPI apps reject or mis-parse anything else.
        val upi = ReminderLinks.upiLink("shop@upi", "Sharma Kirana", 250.0, "udhaar")
        assertTrue(upi.startsWith("upi://pay?"))
        assertTrue(upi.contains("am=250.00"))
        assertTrue(upi.contains("cu=INR"))
    }
}
