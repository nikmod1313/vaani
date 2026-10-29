package app.vaaniledger.extract

import app.vaaniledger.capture.ChatDates
import app.vaaniledger.data.ShopCategory
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The same chat as IosExportTest, read off the SCREEN by the bubble: no
 * export header, each bubble dated from WhatsApp's day divider plus its
 * time label -- the path OverlayService takes.
 */
class ScreenCaptureTest {

    private val today = LocalDate.of(2026, 10, 29)

    private fun turn(day: String, time: String, shop: Boolean, body: String) = ConversationResolver.Turn(
        sender = if (shop) "Aadit" else "Isha",
        body = body,
        timestamp = ChatDates.parse(day, time, today),
        isShopkeeper = shop,
        contentHash = "$day$time$body",
    )

    @Test
    fun `the bubble reads the same three orders`() {
        val d1 = "19 September 2026"; val d2 = "30 September 2026"; val d3 = "28 October 2026"
        val turns = listOf(
            turn(d1, "7:07 pm", false, "hi i need 5 yellow kurtis"),
            turn(d1, "7:07 pm", false, "yellow kurti with polka dots print"),
            turn(d1, "7:07 pm", false, "what is the price?"),
            turn(d1, "7:08 pm", true, "It is for 750"),
            turn(d1, "7:08 pm", false, "ok, i want to buy 3 kurtis"),
            turn(d1, "7:08 pm", false, "i will make payment later"),
            turn(d1, "7:09 pm", true, "Okay and How will you pay?"),
            turn(d1, "7:09 pm", false, "upi"),
            turn(d1, "7:11 pm", false, "sent 2250 to your account using upi"),
            turn(d1, "7:11 pm", true, "Okay received."),
            turn(d2, "7:41 pm", false, "hello bhaiya"),
            turn(d2, "7:41 pm", false, "i need 8 yellow kurtis and 2 blue kurtis"),
            turn(d2, "7:41 pm", false, "what is the price for each?"),
            turn(d2, "7:41 pm", true, "Total 5000"),
            turn(d2, "7:42 pm", true, "Yellow kurtis are for 500 each and blue ones are for 500 each too"),
            turn(d2, "7:42 pm", false, "Okay, I will pay with upi"),
            turn(d2, "7:43 pm", false, "I want yellow kurtis also 8 pieces"),
            turn(d2, "7:44 pm", true, "Okay each is 500"),
            turn(d2, "7:45 pm", false, "i have completed the payment"),
            turn(d3, "1:06 pm", false, "i want 2 kurtis"),
            turn(d3, "1:06 pm", false, "blue color"),
            turn(d3, "1:07 pm", false, "500 rs"),
        )
        val orders = ConversationResolver.resolveAll(turns, "Isha", ShopCategory.APPAREL)
        assertEquals(listOf(2250.0, 5000.0, 500.0), orders.map { it.amount })
        assertEquals("blue", orders[2].items.single().modifier)
    }
}
