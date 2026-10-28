package app.vaaniledger.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * A captured bubble's moment, from the day divider above it and the time
 * label beside it.
 *
 * Without this every captured turn had no date, so a whole screen of chat
 * -- last month's order, last week's payment, today's message -- was read
 * as one conversation. Dated, the threader splits them on the silence
 * between days, exactly as it does for an exported chat.
 *
 * Returns null when the day is not known, which keeps send order; a known
 * day with no readable time gets midnight, which still separates days.
 */
object ChatDates {

    private val DATE_FORMATS = listOf(
        "d MMMM yyyy", "d MMM yyyy", "MMMM d, yyyy", "MMM d, yyyy", "MMMM d yyyy",
        "d/M/yyyy", "d/M/yy", "d.M.yyyy", "d-M-yyyy",
    )
    private val TIME_FORMATS = listOf("h:mm a", "hh:mm a", "H:mm", "HH:mm")

    fun parse(dateLabel: String?, timeLabel: String?, today: LocalDate = LocalDate.now()): LocalDateTime? {
        val day = parseDay(dateLabel ?: return null, today) ?: return null
        return day.atTime(parseTime(timeLabel) ?: LocalTime.MIDNIGHT)
    }

    internal fun parseDay(label: String, today: LocalDate): LocalDate? {
        val t = label.trim()
        when (t.lowercase()) {
            "today", "aaj", "आज" -> return today
            "yesterday", "kal", "कल" -> return today.minusDays(1)
        }
        // A weekday name means the most recent such day before today.
        DayOfWeek.entries.firstOrNull { t.lowercase().startsWith(it.name.lowercase().take(3)) }?.let {
            return today.with(TemporalAdjusters.previous(it))
        }
        for (locale in listOf(Locale.ENGLISH, Locale("hi", "IN"))) {
            for (f in DATE_FORMATS) {
                try {
                    return LocalDate.parse(t, DateTimeFormatter.ofPattern(f, locale))
                } catch (_: DateTimeParseException) {
                }
            }
        }
        return null
    }

    internal fun parseTime(label: String?): LocalTime? {
        if (label.isNullOrBlank()) return null
        // WhatsApp puts a narrow no-break space before AM/PM, and some
        // locales write "a.m.".
        val clean = label.replace(Regex("""[  \s]+"""), " ").replace(".", "").trim().uppercase()
        for (f in TIME_FORMATS) {
            try {
                return LocalTime.parse(clean, DateTimeFormatter.ofPattern(f, Locale.ENGLISH))
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }
}
