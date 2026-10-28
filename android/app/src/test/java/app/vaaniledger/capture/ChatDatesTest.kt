package app.vaaniledger.capture

import app.vaaniledger.reminder.ReminderLinks
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The captured-chat fixes from a real phone's screenshot. */
class ChatDatesTest {

    private val today = LocalDate.of(2026, 10, 28)

    /** "10 September 2026" was read as a customer message. */
    @Test
    fun `whatsapp day dividers are recognised in every form`() {
        for (label in listOf("10 September 2026", "September 10, 2026", "10/09/2026", "Today", "Yesterday", "Monday")) {
            assertTrue(label, ChatScraper.DATE_PATTERN.matches(label))
        }
        assertFalse(ChatScraper.DATE_PATTERN.matches("2 kg aloo bhej do"))
        assertFalse(ChatScraper.DATE_PATTERN.matches("i need 2 kilos"))
    }

    @Test
    fun `a bubble is dated from its divider and its time`() {
        assertEquals(LocalDateTime.of(2026, 9, 10, 21, 5), ChatDates.parse("10 September 2026", "9:05 pm", today))
        assertEquals(LocalDateTime.of(2026, 10, 27, 0, 0), ChatDates.parse("Yesterday", null, today))
        assertEquals(LocalDateTime.of(2026, 10, 28, 14, 30), ChatDates.parse("Today", "14:30", today))
        assertNull(ChatDates.parse(null, "9:05 pm", today))
    }

    /** The shop's own reminder carried Rs 1,000 into the next capture's evidence. */
    @Test
    fun `vaani's own reminders are recognised`() {
        assertTrue(ReminderLinks.isReminder("Namaste test ji 🙏 Sharma Kirana Store se — aapka ₹1,000 baaki hai (19 Sep tak)."))
        assertTrue(ReminderLinks.isReminder(ReminderLinks.composeMessage("Rahul", "Gupta Store", 500.0)))
        assertTrue(ReminderLinks.isReminder(ReminderLinks.composeMessage("Rahul", "Gupta Store", 500.0, language = "en-IN")))
        assertFalse(ReminderLinks.isReminder("namaste bhaiya 2 kg aloo bhej do"))
    }
}
