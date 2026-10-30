package app.vaaniledger.extract

import app.vaaniledger.data.CatalogueItem
import app.vaaniledger.net.Confidence
import app.vaaniledger.net.Extraction
import app.vaaniledger.net.FieldScore
import app.vaaniledger.net.LineItem

/**
 * Read an extraction against the shop's own price list.
 *
 * Three things, in order:
 *  1. A line the engine found is matched to its list entry, taking the
 *     list's name ("muslin kurti", not "kurti") and, when the message gave
 *     no rate, the list's price.
 *  2. A list item named in the message but missed by the engine -- an
 *     unusual name, a typo one letter off -- is added as a line.
 *  3. No total stated, every line priced: the total is the sum, and is
 *     marked as coming from the price list so the confirm sheet asks.
 *
 * A price the message states always wins over the list: the list is what
 * the shop usually charges, the message is what it charged this time.
 *
 * Pure, and applied after the engine rather than inside it, so the engine's
 * parity with the Python original is untouched.
 */
object CatalogueMatch {

    const val SIGNAL = "price-list"

    fun enrich(e: Extraction, catalogue: List<CatalogueItem>): Extraction {
        if (catalogue.isEmpty() || e.direction == "payment") return e
        val entries = catalogue.map { it to words(it.name) }.filter { it.second.isNotEmpty() }
            // Most specific first, so "muslin kurti" claims a line before "kurti".
            .sortedByDescending { it.second.size }

        val claimed = mutableSetOf<CatalogueItem>()
        val lines = e.lineItems.map { line ->
            val lineWords = words(line.displayName.ifBlank { line.name })
            val hit = entries.firstOrNull { (item, w) ->
                item !in claimed && w.all { cw -> lineWords.any { same(it, cw) } }
            }
                ?: entries.firstOrNull { (item, w) ->
                    // Engine kept only the noun; the message had the full name.
                    item !in claimed && same(w.last(), lineWords.lastOrNull() ?: "") &&
                        containsRun(words(e.text), w)
                }
            if (hit == null) line else {
                claimed += hit.first
                val counted = if (line.quantity == null) countAfter(words(e.text), hit.second, hit.first.price) else null
                priced(line.copy(displayName = hit.first.name, quantity = counted ?: line.quantity), hit.first)
            }
        }.toMutableList()

        // Named in the message, missed by the engine.
        val text = words(e.text)
        for ((item, w) in entries) {
            if (item in claimed) continue
            val at = indexOfRun(text, w)
            if (at < 0) continue
            // Same noun already on a line (e.g. a plain "kurti"): leave it.
            if (lines.any { same(words(it.displayName).lastOrNull() ?: "", w.last()) }) continue
            claimed += item
            val qty = text.getOrNull(at - 1)?.let { NumberParser.parseNumber(it) }
                ?.takeIf { it > 0 && it < 1000 }
            lines += priced(
                LineItem(name = w.last(), modifier = w.dropLast(1).joinToString(" ").ifBlank { null },
                    displayName = item.name, quantity = qty, unit = null),
                item,
            )
        }

        if (claimed.isEmpty()) return e
        val labelled = lines.map { it.copy(label = label(it)) }

        val sum = labelled.takeIf { l -> l.isNotEmpty() && l.all { it.lineTotal != null } }
            ?.sumOf { it.lineTotal!! }
        val fromList = e.amount == null && sum != null && sum > 0
        return e.copy(
            items = labelled.map { it.label },
            lineItems = labelled,
            amount = if (fromList) sum else e.amount,
            direction = if (e.direction == "unknown" && fromList) "sale" else e.direction,
            signals = e.signals + SIGNAL,
            confidence = if (!fromList) e.confidence else e.confidence.withAmountFromList(),
        )
    }

    private fun Confidence.withAmountFromList() = copy(
        route = if (route == "auto") "ask" else if (route == "manual") "ask" else route,
        fields = fields + ("amount" to FieldScore(0.7, "priced from your price list")),
        askAbout = (askAbout + "amount").distinct(),
    )

    private fun priced(line: LineItem, item: CatalogueItem): LineItem {
        val rate = line.unitPrice ?: item.price ?: return line
        val qty = line.quantity ?: 1.0
        // A list price is per the shop's usual unit: "spring onion 200" is
        // Rs 200 a kilo. An order in grams is a fraction of that kilo, not
        // five hundred kilos -- "250 gram" at 200 booked Rs 50,000. Only the
        // list's price is converted; a rate the message stated is per the
        // message's own unit already.
        val factor = if (line.unitPrice != null) 1.0 else conversion(line.unit, item.unit)
        val billed = qty * factor
        return line.copy(unitPrice = rate, quantity = qty, lineTotal = Math.round(rate * billed * 100.0) / 100.0)
    }

    /** How many list units one ordered unit is: g against a per-kg price is 1/1000. */
    internal fun conversion(ordered: String?, listed: String?): Double {
        val o = canonicalUnit(ordered)
        val l = canonicalUnit(listed)
        return when {
            o == "g" && (l == null || l == "kg") -> 0.001
            o == "ml" && (l == null || l == "l") -> 0.001
            o == "kg" && l == "g" -> 1000.0
            o == "l" && l == "ml" -> 1000.0
            else -> 1.0
        }
    }

    private fun canonicalUnit(u: String?): String? = when (u?.lowercase()?.trim()) {
        null, "" -> null
        "kg", "kgs", "kilo", "kilos", "kilogram" -> "kg"
        "g", "gm", "gms", "gram", "grams" -> "g"
        "l", "ltr", "litre", "liter", "litres", "liters" -> "l"
        "ml" -> "ml"
        else -> u.lowercase().trim()
    }

    /** Words that make a number after an item a price or a measure, not a count. */
    private val NOT_A_COUNT = setOf(
        "rs", "rupaye", "rupees", "rupay", "ka", "ki", "ke", "each", "per", "kg", "kilo", "g", "gm", "gram",
        "grams", "l", "litre", "ml", "packet", "pkt", "box", "dozen",
    )

    /**
     * "spring onion 2": the number straight after a listed item, small, whole,
     * and not a price or a measure, is how many. Read only for items on the
     * list -- the list already says what one costs, so the number cannot be
     * that price unless it equals it.
     */
    private fun countAfter(text: List<String>, name: List<String>, price: Double?): Double? {
        val at = indexOfRun(text, name)
        if (at < 0) return null
        val n = text.getOrNull(at + name.size)?.toDoubleOrNull() ?: return null
        val next = text.getOrNull(at + name.size + 1)
        if (n <= 0 || n > 50 || n != Math.floor(n) || n == price || next in NOT_A_COUNT) return null
        return n
    }

    private fun label(line: LineItem): String {
        val q = line.quantity ?: return line.displayName
        val qty = if (q == Math.floor(q)) q.toInt().toString() else q.toString()
        return "${line.displayName} $qty${line.unit ?: ""}".trim()
    }

    internal fun words(s: String): List<String> =
        s.lowercase().split(Regex("""[^\p{L}\p{M}\p{N}]+""")).filter { it.isNotBlank() }

    private fun containsRun(text: List<String>, run: List<String>) = indexOfRun(text, run) >= 0

    private fun indexOfRun(text: List<String>, run: List<String>): Int {
        if (run.isEmpty() || text.size < run.size) return -1
        for (i in 0..text.size - run.size) {
            if (run.indices.all { same(text[i + it], run[it]) }) return i
        }
        return -1
    }

    /** Plurals, and one slip of the thumb in a longer word. */
    internal fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val x = stem(a)
        val y = stem(b)
        if (x == y) return true
        return minOf(x.length, y.length) >= 5 && withinOne(x, y)
    }

    private fun stem(w: String) = when {
        w.length > 4 && w.endsWith("es") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") -> w.dropLast(1)
        else -> w
    }

    private fun withinOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
