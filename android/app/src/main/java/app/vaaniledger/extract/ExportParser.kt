package app.vaaniledger.extract

import app.vaaniledger.extract.ConversationResolver.Turn
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * WhatsApp "Export chat" parsing, on the phone.
 *
 * A port of the parser half of `backend/app/extraction/whatsapp.py`. With
 * this and [ConversationResolver] on-device, an exported chat goes from file
 * to review queue with no network at all -- which is the difference between
 * an app a shopkeeper can install and one that needs someone's laptop
 * running on the same wifi.
 *
 * CONTENT HASHES MUST MATCH THE SERVER'S BYTE FOR BYTE. The watermark that
 * stops an order being booked twice is a set of these hashes, and the same
 * chat can reach the ledger through this parser today and through the server
 * tomorrow. If the two hash a message differently, the second import does
 * not know the first happened. The formula, the timestamp format and the
 * normalisation are therefore copied exactly, not approximated.
 */
object ExportParser {

    data class Message(
        val timestamp: LocalDateTime?,
        val sender: String,
        val body: String,
    ) {
        /**
         * Stable identity. Sender + timestamp + normalised body, SHA-1, the
         * same as `Message.content_hash` on the server.
         */
        val contentHash: String
            get() {
                val ts = timestamp?.format(ISO) ?: ""
                val payload = "${sender.trim().lowercase()}|$ts|${NumberParser.normalise(body)}"
                return MessageDigest.getInstance("SHA-1")
                    .digest(payload.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            }
    }

    // Python's datetime.isoformat() always writes seconds; LocalDateTime's
    // toString() drops them when zero, which would change every hash.
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    // [26/08/26, 9:41:23 AM] Rahul Verma: message
    private val BRACKETED = Regex(
        """^\[(?<date>\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}),?\s+""" +
            """(?<time>\d{1,2}:\d{2}(?::\d{2})?\s*(?:[APap]\.?[Mm]\.?)?)\]\s*""" +
            """(?<sender>[^:]{1,60}?):\s(?<body>.*)$"""
    )

    // 26/08/26, 9:41 am - Rahul Verma: message   (hyphen or en/em dash)
    private val DASHED = Regex(
        """^(?<date>\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}),?\s+""" +
            """(?<time>\d{1,2}:\d{2}(?::\d{2})?\s*(?:[APap]\.?[Mm]\.?)?)\s+[-–—]\s+""" +
            """(?<sender>[^:]{1,60}?):\s(?<body>.*)$"""
    )

    /** System lines carry no sender and must never become ledger entries. */
    private val SYSTEM_MARKERS = listOf(
        "messages and calls are end-to-end encrypted",
        "you deleted this message", "this message was deleted",
        "<media omitted>", "media omitted",
        "missed voice call", "missed video call",
        "changed the subject", "changed this group's icon",
        "created group", "added you", "left",
        "joined using this group's invite link",
        "security code changed", "null",
    )

    private val DATE_FORMATS = listOf(
        "d/M/yy", "d/M/yyyy", "M/d/yy", "M/d/yyyy",
        "d-M-yy", "d-M-yyyy", "d.M.yy", "d.M.yyyy",
    )
    private val TIME_FORMATS = listOf("h:mm:ss a", "h:mm a", "H:mm:ss", "H:mm")

    private fun parseTimestamp(date: String, time: String): LocalDateTime? {
        // Collapse the U+202F / U+00A0 WhatsApp puts before AM/PM, drop the
        // dots in "a.m.", and uppercase for Locale.US's "AM"/"PM".
        val cleanTime = time.trim().replace(".", "")
            .replace(Regex("""[\s\u202f\u00a0]+"""), " ").uppercase()
        for (df in DATE_FORMATS) {
            for (tf in TIME_FORMATS) {
                try {
                    val fmt = DateTimeFormatter.ofPattern("$df $tf", Locale.US)
                    return LocalDateTime.parse("$date $cleanTime", fmt)
                } catch (_: DateTimeParseException) {
                    // try the next shape
                }
            }
        }
        return null
    }

    private fun isSystemLine(body: String): Boolean {
        val low = body.trim().lowercase()
        return SYSTEM_MARKERS.any { low.contains(it) }
    }

    /**
     * Parse an export into messages.
     *
     * Continuation lines (a message containing newlines) carry no timestamp
     * prefix and are appended to the message above them.
     */
    fun parse(content: String): List<Message> {
        val messages = mutableListOf<Message>()
        for (line in content.lineSequence()) {
            // WhatsApp prefixes exports with a U+200E left-to-right mark.
            val stripped = line.trimStart('\u200e', '\u200f').trimEnd()
            if (stripped.isEmpty()) continue

            val match = BRACKETED.find(stripped) ?: DASHED.find(stripped)
            if (match != null) {
                val body = match.groups["body"]!!.value.trim()
                if (isSystemLine(body)) continue
                messages.add(
                    Message(
                        timestamp = parseTimestamp(
                            match.groups["date"]!!.value, match.groups["time"]!!.value,
                        ),
                        sender = match.groups["sender"]!!.value.trim(),
                        body = body,
                    )
                )
            } else if (messages.isNotEmpty()) {
                val last = messages.removeAt(messages.lastIndex)
                messages.add(last.copy(body = last.body + "\n" + stripped))
            }
        }
        return messages
    }

    // ----------------------------------------------------------------------
    // Sides
    // ----------------------------------------------------------------------

    /** Names the export uses for the phone's own owner. */
    private val SELF_ALIASES = setOf("you", "me", "aap", "आप")

    /**
     * Which side of the chat sent this?
     *
     * Decided by naming the CUSTOMER rather than the shop. A 1:1 chat has
     * exactly two participants and the customer's name is the one thing the
     * app reliably knows.
     */
    fun isShopkeeper(sender: String, customer: String, aliases: Set<String>): Boolean {
        val who = sender.trim().lowercase()
        if (who in SELF_ALIASES) return true
        if (who in aliases.map { it.trim().lowercase() }) return true
        return who != customer.trim().lowercase()
    }

    /**
     * Work out which participant is the customer.
     *
     * An export written on the shopkeeper's phone names the owner by their
     * OWN contact name, not "You". Eliminate the shop's aliases and the
     * self-aliases, and in a 1:1 chat exactly one participant is left. The
     * most talkative remaining sender wins when several are -- right for a
     * group, harmless for a 1:1. Null when nobody qualifies, and the caller
     * has to ask rather than guess.
     */
    fun inferCustomer(messages: List<Message>, aliases: Set<String>): String? {
        val excluded = aliases.map { it.trim().lowercase() }.toSet() + SELF_ALIASES
        val counts = mutableMapOf<String, Int>()
        for (m in messages) {
            val who = m.sender.trim()
            if (who.lowercase() in excluded) continue
            counts[who] = (counts[who] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }

    fun toTurns(messages: List<Message>, customer: String, aliases: Set<String>): List<Turn> =
        messages.map { m ->
            Turn(
                sender = m.sender,
                body = m.body,
                timestamp = m.timestamp,
                isShopkeeper = isShopkeeper(m.sender, customer, aliases),
                contentHash = m.contentHash,
            )
        }
}
