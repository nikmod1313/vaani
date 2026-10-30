package app.vaaniledger.reminder

import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Assisted reminders.
 *
 * This is how reminders ship without the WhatsApp Business API. We compose
 * the message and a UPI link, open WhatsApp with it pre-filled, and the
 * shopkeeper taps send himself.
 *
 * Two things follow from that, and both are features rather than compromises:
 * it works with any customer today with no approval or verification, and no
 * message ever leaves the phone without a human seeing it first.
 */
object ReminderLinks {

    private fun dateFormat(language: String) =
        SimpleDateFormat("d MMM", if (language == "en-IN") Locale("en", "IN") else Locale("hi", "IN"))

    /**
     * Normalise an Indian number to the E.164 digits wa.me expects.
     *
     * wa.me takes digits only -- no plus, no spaces, no dashes. A saved
     * contact is just as likely to read "+91 98765 43210" or "098765 43210",
     * and any of those sent raw silently opens WhatsApp on a blank chat.
     *
     * @return digits with country code, or null if it cannot be trusted.
     */
    fun normalisePhone(raw: String?, defaultCountryCode: String = "91"): String? {
        if (raw.isNullOrBlank()) return null
        var digits = raw.filter { it.isDigit() }

        // Trunk prefix used when dialling domestically.
        if (digits.length == 11 && digits.startsWith("0")) {
            digits = digits.substring(1)
        }
        // International dialling prefix.
        if (digits.startsWith("00")) {
            digits = digits.substring(2)
        }

        return when {
            digits.length == 10 -> defaultCountryCode + digits
            digits.length in 11..15 -> digits
            else -> null   // too short or too long to be a real number
        }
    }

    /**
     * The reminder text.
     *
     * Deliberately polite and specific: it names the shop, the amount and the
     * date, because a vague demand for money from an unknown sender reads as
     * a scam and gets ignored.
     */
    fun composeMessage(
        customerName: String,
        shopName: String,
        amount: Double,
        asOf: Date = Date(),
        language: String = "hi-IN",
        /**
         * Whether a pay link follows. The line pointing at "the link below"
         * is only written when there is one -- without the shop's UPI ID
         * set, it sent customers looking for a link that was not there.
         */
        withLink: Boolean = false,
    ): String {
        val rupees = "₹%,.0f".format(amount)
        val date = dateFormat(language).format(asOf)

        return when (language) {
            "en-IN" -> "Namaste $customerName 🙏 This is $shopName. Your balance " +
                "of $rupees is pending as of $date. " +
                (if (withLink) "You can pay using the link below. " else "") + "Thank you!"

            else -> "नमस्ते $customerName जी 🙏 $shopName की ओर से — आपके $rupees " +
                "बाकी हैं ($date तक)। " +
                (if (withLink) "नीचे टैप करके भुगतान कर सकते हैं। " else "") + "धन्यवाद!"
        }
    }

    /**
     * A standard UPI intent link. Works with any UPI app installed.
     *
     * `am` is fixed to two decimals and `cu` to INR because several UPI apps
     * reject or silently mis-parse anything else.
     */
    fun upiLink(
        payeeVpa: String,
        payeeName: String,
        amount: Double,
        note: String,
    ): String {
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        return buildString {
            append("upi://pay")
            append("?pa=").append(encode(payeeVpa))
            append("&pn=").append(encode(payeeName))
            append("&am=").append("%.2f".format(amount))
            append("&cu=INR")
            append("&tn=").append(encode(note))
        }
    }

    /**
     * wa.me link with the message pre-filled.
     *
     * URLEncoder encodes a space as "+", which wa.me renders literally as a
     * plus sign in the message box. It has to be %20.
     */
    fun whatsAppLink(phone: String?, message: String): String {
        val encoded = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
        val number = normalisePhone(phone)
        return if (number != null) {
            "https://wa.me/$number?text=$encoded"
        } else {
            // No usable number: open the share sheet instead of a broken chat.
            "https://wa.me/?text=$encoded"
        }
    }

    /**
     * The full reminder, ready to hand to startActivity.
     *
     * Targets WhatsApp explicitly so it does not land in a browser, and falls
     * back to an untargeted intent when WhatsApp is not installed.
     */
    fun reminderIntent(
        phone: String?,
        customerName: String,
        shopName: String,
        amount: Double,
        payeeVpa: String? = null,
        language: String = "hi-IN",
    ): Intent {
        val message = buildString {
            append(composeMessage(customerName, shopName, amount, language = language, withLink = payeeVpa != null))
            if (payeeVpa != null) {
                append("\n\n")
                append(upiLink(payeeVpa, shopName, amount, "$shopName udhaar"))
            }
        }

        return Intent(Intent.ACTION_VIEW, Uri.parse(whatsAppLink(phone, message))).apply {
            setPackage(WHATSAPP_PACKAGE)
        }
    }

    /** Use when [reminderIntent] cannot resolve -- WhatsApp is not installed. */
    fun untargetedFallback(intent: Intent): Intent =
        Intent(intent).apply { setPackage(null) }

    /**
     * Is this one of Vaani's own reminders, sent from this phone?
     *
     * Read back off the chat on the next capture, "Namaste ... Rs 1,000
     * baaki hai" carried an amount and landed in the order's evidence -- a
     * figure the shop never quoted for anything. Matches every language the
     * reminder has been written in.
     */
    fun isReminder(text: String): Boolean {
        val t = text.lowercase()
        val greets = t.contains("namaste") || t.contains("नमस्ते")
        val owes = t.contains("baaki hai") || t.contains("बाकी हैं") || t.contains("is pending as of")
        return greets && owes
    }

    /** A Vaani reminder that carried a UPI pay link. Mirrors `_is_pay_link_reminder`. */
    fun carriesPayLink(text: String): Boolean =
        isReminder(text) && text.lowercase().contains("upi://pay")

    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
}
