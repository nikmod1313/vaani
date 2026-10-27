package app.vaaniledger.extract

import java.text.Normalizer

/**
 * Hindi / Hinglish number-word parsing, on-device.
 *
 * A faithful Kotlin port of `backend/app/extraction/numbers.py`. The Python
 * copy stays the reference implementation and keeps its 214 tests; this one
 * exists so the phone can read an order with no server and no signal, which
 * is where this app is actually used -- a shop counter, mid-sale.
 *
 * TWO REGEX DIFFERENCES BETWEEN PYTHON AND JAVA MATTER HERE, and both were
 * worth getting right rather than discovering later on a wrong number:
 *
 * 1. Python's `\w` is Unicode-aware; Java's is ASCII-only unless you pass
 *    UNICODE_CHARACTER_CLASS. The junk-stripping pattern below therefore uses
 *    explicit `\p{L}\p{N}` classes, which are Unicode-aware in Java, instead
 *    of `\w`. Using `\w` here would have silently deleted every Devanagari
 *    character in the message.
 *
 * 2. `\b` is defined against `\w`, so it is unusable around Devanagari in
 *    BOTH languages. The Python source already works around this with
 *    whitespace lookarounds `(?<!\S)` / `(?!\S)`, and those translate to Java
 *    unchanged -- see RulesEngine.
 *
 * `to_words` (number -> Hindi words) is deliberately NOT ported. It only
 * feeds the spoken summary, which is a server feature; porting it would be
 * dead code on the phone.
 */
object NumberParser {

    // Marks a clause break. A number phrase never spans one.
    //
    // NUL, exactly as in the Python source, and deliberately NOT a space:
    // tokenize() splits on whitespace, so a whitespace boundary marker
    // would be erased by the very function that has to preserve it -- and
    // "chai bhej do, dhai sau" would parse back to 450 instead of 250.
    const val BOUNDARY = "\u0000"

    // ----------------------------------------------------------------------
    // Lexicon
    // ----------------------------------------------------------------------

    // Units 0-10 plus the irregular 11-99 forms. Hindi numerals are irregular
    // the whole way to 100, so there is no generating rule -- it has to be a
    // table.
    private val UNITS: Map<String, Double> = buildMap {
        putAll(
            mapOf(
                "shunya" to 0.0, "zero" to 0.0,
                "ek" to 1.0, "do" to 2.0, "teen" to 3.0, "tin" to 3.0,
                "char" to 4.0, "chaar" to 4.0,
                "panch" to 5.0, "paanch" to 5.0, "chhe" to 6.0, "chah" to 6.0,
                "saat" to 7.0, "aath" to 8.0, "ath" to 8.0, "nau" to 9.0,
                "das" to 10.0, "dus" to 10.0,
                "gyarah" to 11.0, "gyara" to 11.0, "barah" to 12.0, "bara" to 12.0,
                "terah" to 13.0, "tera" to 13.0, "chaudah" to 14.0, "chauda" to 14.0,
                "pandrah" to 15.0, "pandra" to 15.0, "solah" to 16.0, "sola" to 16.0,
                "satrah" to 17.0, "satra" to 17.0, "atharah" to 18.0, "athara" to 18.0,
                "unnis" to 19.0, "unis" to 19.0, "bees" to 20.0, "bis" to 20.0,
                "ikkis" to 21.0, "bais" to 22.0, "teis" to 23.0, "chaubis" to 24.0,
                "pachchis" to 25.0, "pacchis" to 25.0, "chhabbis" to 26.0,
                "sattais" to 27.0, "atthais" to 28.0, "untis" to 29.0,
                "tees" to 30.0, "tis" to 30.0, "ikattis" to 31.0, "battis" to 32.0,
                "taintis" to 33.0, "chauntis" to 34.0, "paintis" to 35.0,
                "chhattis" to 36.0, "saintis" to 37.0, "adtis" to 38.0,
                "untalis" to 39.0, "chalis" to 40.0, "chalees" to 40.0,
                "iktalis" to 41.0, "bayalis" to 42.0, "taintalis" to 43.0,
                "chavalis" to 44.0, "paintalis" to 45.0, "chhiyalis" to 46.0,
                "santalis" to 47.0, "adtalis" to 48.0, "unchas" to 49.0,
                "pachas" to 50.0, "pachaas" to 50.0,
                "ikyavan" to 51.0, "bavan" to 52.0, "tirpan" to 53.0,
                "chauvan" to 54.0, "pachpan" to 55.0, "chhappan" to 56.0,
                "sattavan" to 57.0, "atthavan" to 58.0, "unsath" to 59.0,
                "saath" to 60.0, "sath" to 60.0, "iksath" to 61.0, "basath" to 62.0,
                "tirsath" to 63.0, "chausath" to 64.0, "painsath" to 65.0,
                "chhiyasath" to 66.0, "sarsath" to 67.0, "adsath" to 68.0,
                "unhattar" to 69.0, "sattar" to 70.0, "ikhattar" to 71.0,
                "bahattar" to 72.0, "tihattar" to 73.0, "chauhattar" to 74.0,
                "pachhattar" to 75.0, "chihattar" to 76.0, "sathattar" to 77.0,
                "athhattar" to 78.0, "unasi" to 79.0, "assi" to 80.0,
                "ikyasi" to 81.0, "byasi" to 82.0, "tirasi" to 83.0,
                "chaurasi" to 84.0, "pachasi" to 85.0, "chhiyasi" to 86.0,
                "satasi" to 87.0, "athasi" to 88.0, "navasi" to 89.0,
                "nabbe" to 90.0, "ikyanve" to 91.0, "banve" to 92.0,
                "tiranve" to 93.0, "chauranve" to 94.0, "pachanve" to 95.0,
                "chhiyanve" to 96.0, "sattanve" to 97.0, "atthanve" to 98.0,
                "ninyanve" to 99.0,
            )
        )
        // Devanagari spellings map onto the same values.
        putAll(
            mapOf(
                "एक" to 1.0, "दो" to 2.0, "तीन" to 3.0, "चार" to 4.0,
                "पांच" to 5.0, "पाँच" to 5.0, "छह" to 6.0, "छे" to 6.0,
                "सात" to 7.0, "आठ" to 8.0, "नौ" to 9.0, "दस" to 10.0,
                "ग्यारह" to 11.0, "बारह" to 12.0, "तेरह" to 13.0, "चौदह" to 14.0,
                "पंद्रह" to 15.0, "सोलह" to 16.0, "सत्रह" to 17.0, "अठारह" to 18.0,
                "उन्नीस" to 19.0, "बीस" to 20.0, "पच्चीस" to 25.0, "तीस" to 30.0,
                "चालीस" to 40.0, "पचास" to 50.0, "साठ" to 60.0, "सत्तर" to 70.0,
                "अस्सी" to 80.0, "नब्बे" to 90.0,
            )
        )
    }

    // Multipliers. `sau` scales the pending value; hazaar and up close a group.
    //
    // Deliberately NOT included: "so" (English), "k" (appears everywhere).
    // Their collision rate with ordinary text outweighs the recall they buy.
    private val MULTIPLIERS: Map<String, Int> = mapOf(
        "sau" to 100, "सौ" to 100,
        "hazaar" to 1000, "hazar" to 1000, "hajar" to 1000, "hajaar" to 1000,
        "sahasra" to 1000, "हज़ार" to 1000, "हजार" to 1000,
        "lakh" to 100_000, "lac" to 100_000, "lakhs" to 100_000, "लाख" to 100_000,
        "crore" to 10_000_000, "karod" to 10_000_000, "करोड़" to 10_000_000,
    )

    /** Standalone fractional quantities. */
    private val FRACTIONS: Map<String, Double> = mapOf(
        "adha" to 0.5, "aadha" to 0.5, "adh" to 0.5, "half" to 0.5, "आधा" to 0.5,
        "pav" to 0.25, "paav" to 0.25, "पाव" to 0.25,
        "dedh" to 1.5, "derh" to 1.5, "dedhh" to 1.5, "डेढ़" to 1.5, "डेढ" to 1.5,
        "dhai" to 2.5, "dhaai" to 2.5, "adhai" to 2.5, "adhaai" to 2.5,
        "ढाई" to 2.5, "अढ़ाई" to 2.5,
    )

    // Prefix modifiers -- they adjust the number that FOLLOWS them. With no
    // following number, an implied 1 is used: "sava sau" -> 1.25 * 100.
    private val MODIFIERS: Map<String, Double> = mapOf(
        "sava" to 0.25, "sawa" to 0.25, "सवा" to 0.25,
        "paune" to -0.25, "pone" to -0.25, "पौने" to -0.25,
        "sadhe" to 0.5, "sade" to 0.5, "साढ़े" to 0.5, "साढे" to 0.5,
    )

    /** Words that may sit between number tokens without breaking the run. */
    private val FILLERS = setOf("aur", "और", "or")

    // Number words that are also ordinary Hindi words. "do" means 2, but it is
    // far more often the imperative "give/send" -- "bhej do", "kar do", "likh
    // do". A weak word only counts as a number when the context supports it.
    private val WEAK_NUMBERS = setOf("do", "de", "दो", "दे")

    /** Verb stems that turn a following "do" into an imperative particle. */
    private val IMPERATIVE_STEMS = setOf(
        "bhej", "kar", "likh", "la", "laa", "de", "daal", "dal", "rakh",
        "bana", "banaa", "chadha", "utha", "bhijwa", "dedo", "send", "add",
        "भेज", "कर", "लिख", "ला", "दे", "डाल", "रख",
    )

    // ----------------------------------------------------------------------
    // Normalisation
    // ----------------------------------------------------------------------

    private val DEVA_DIGITS = "०१२३४५६७८९"

    private val RE_CURRENCY_PREFIX = Regex("""(₹|रु\.?)\s*(\d)""")
    private val RE_RS_PREFIX = Regex("""\brs\.?\s*(\d)""")
    private val RE_TRAILING_SLASH = Regex("""(\d)\s*/-""")
    private val RE_PHONE = Regex(
        """\+?91[\s-]?[6-9]\d{4}[\s-]?\d{5}(?!\d)|(?<!\d)[6-9]\d{9}(?!\d)|(?<!\d)[6-9]\d{4}[\s-]\d{5}(?!\d)""" +
            """|\(\d{3}\)\s?\d{3}[\s.-]\d{4}(?!\d)|(?<!\d)\d{3}[.-]\d{3}[.-]\d{4}(?!\d)"""
    )
    private val RE_DIGIT_GROUP = Regex("""(?<=\d),(?=\d{2},\d|\d{3}(?!\d))""")
    private val RE_GLUED_UNIT = Regex(
        """(?<=\d)(kgs?|kilos?|gms?|grams?|ml|ltrs?|litres?|liters?|l|pkts?|packets?|pcs?)\b"""
    )
    private val RE_CLAUSE_SEPARATORS = Regex("""[,،;:।!?\n\r]+""")
    private val RE_STANDALONE_PERIOD = Regex("""(?<!\d)\.(?!\d)""")
    // \p{L}\p{N} rather than \w: Java's \w is ASCII-only, and \w here would
    // strip every Devanagari character in the message.
    private val RE_JUNK = Regex("""[^\p{L}\p{N}\s.ऀ-ॿ₹/\u0000-]""")
    private val RE_SPACES = Regex("""[ \t]+""")
    private val RE_NUMERIC = Regex("""\d+(?:\.\d+)?""")

    /**
     * Lowercase, fold Devanagari digits to ASCII, and mark clause boundaries.
     *
     * Boundaries matter: without them "chai bhej do, dhai sau" parses the
     * imperative "do" as 2 and yields 450 instead of 250.
     */
    fun normalise(input: String): String {
        var text = Normalizer.normalize(input, Normalizer.Form.NFC)

        // Devanagari digits -> ASCII.
        text = buildString(text.length) {
            for (ch in text) {
                val index = DEVA_DIGITS.indexOf(ch)
                append(if (index >= 0) ('0' + index) else ch)
            }
        }

        text = text.lowercase()
        // A phone number is never money. Customers send them -- "is number
        // pe call karna", a delivery contact -- and a captured "Save (987)
        // 650-0001?" banner was read as a Rs 987 bill. Removed before any
        // number is read. Mirrors numbers.py.
        text = RE_PHONE.replace(text, " ")
        // Detach currency markers from the digits they hug, so "₹250",
        // "rs.250" and "250/-" all yield a bare numeric token.
        text = RE_CURRENCY_PREFIX.replace(text) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        text = RE_RS_PREFIX.replace(text) { "rs ${it.groupValues[1]}" }
        text = RE_TRAILING_SLASH.replace(text) { "${it.groupValues[1]} " }
        // Digit-group commas belong to the number: "1,250", the Indian
        // "1,25,000". Left in, the comma became a clause boundary and
        // "total 1,250 hua" was read as Rs 250. Mirrors numbers.py.
        text = RE_DIGIT_GROUP.replace(text, "")
        // Split glued measures: people type "atta 5kg, dal 2kg" far more often
        // than "5 kg". Without this the unit never registers and the item
        // loses its quantity entirely.
        text = RE_GLUED_UNIT.replace(text) { " ${it.groupValues[1]}" }
        // Clause separators become explicit boundary tokens. A period only
        // counts when it is not a decimal point.
        text = RE_CLAUSE_SEPARATORS.replace(text, " $BOUNDARY ")
        text = RE_STANDALONE_PERIOD.replace(text, " $BOUNDARY ")
        text = RE_JUNK.replace(text, " ")
        return RE_SPACES.replace(text, " ").trim()
    }

    private val RE_WHITESPACE = Regex("""\s+""")

    // Tokens, with BOUNDARY markers preserved.
    //
    // Splits on any whitespace run, matching Python's bare `str.split()`.
    // NUL is not whitespace in either engine, so a BOUNDARY marker survives
    // as its own token -- which is the whole point of it.
    fun tokenize(text: String): List<String> =
        normalise(text).split(RE_WHITESPACE).filter { it.isNotEmpty() }

    private fun isNumeric(token: String): Boolean = RE_NUMERIC.matches(token)

    /** True if the token participates in a number phrase. */
    fun isNumberToken(token: String): Boolean {
        val t = token.trim('.')
        if (t == BOUNDARY || t.isEmpty()) return false
        return t in UNITS || t in MULTIPLIERS || t in FRACTIONS ||
            t in MODIFIERS || isNumeric(t)
    }

    /**
     * Decide whether a weak number word is actually a verb here.
     *
     * "do" counts as 2 when it leads into a unit or another number ("do
     * kilo", "do hazaar"). It is the imperative "give" when it trails a verb
     * stem ("bhej do") or ends the clause ("likh do.").
     */
    private fun isVerbNotNumber(tokens: List<String>, i: Int): Boolean {
        val token = tokens[i].trim('.')
        if (token !in WEAK_NUMBERS) return false

        val prev = if (i > 0) tokens[i - 1].trim('.') else ""
        if (prev in IMPERATIVE_STEMS) return true

        val next = if (i + 1 < tokens.size) tokens[i + 1].trim('.') else BOUNDARY
        // Nothing meaningful follows -> imperative.
        if (next == BOUNDARY || next.isEmpty()) return true
        // Followed by a multiplier or another number, it is a real quantity.
        if (next in MULTIPLIERS || isNumberToken(next)) return false
        // Followed by a unit or item word, also a real quantity ("do kilo").
        return false
    }

    // ----------------------------------------------------------------------
    // Parsing
    // ----------------------------------------------------------------------

    /**
     * Parse a run of number tokens into a single value.
     *
     * Uses the standard total/current accumulator: `sau` scales the pending
     * value, while `hazaar` and above close the current group and add it to
     * the running total. That makes compounds work -- "do hazaar paanch sau"
     * is 2*1000 then 5*100, giving 2500.
     */
    fun parseNumberPhrase(tokens: List<String>): Double? {
        var total = 0.0
        var current: Double? = null
        var modifier: Double? = null
        var seenAny = false

        for (raw in tokens) {
            val token = raw.trim('.')
            if (token.isEmpty() || token in FILLERS) continue

            val asModifier = MODIFIERS[token]
            if (asModifier != null) {
                modifier = asModifier
                seenAny = true
                continue
            }

            val asFraction = FRACTIONS[token]
            if (asFraction != null) {
                current = (current ?: 0.0) + asFraction
                seenAny = true
                continue
            }

            if (isNumeric(token)) {
                var value = token.toDouble()
                val pending = modifier
                if (pending != null) {
                    value += pending
                    modifier = null
                }
                val running = current
                current = if (running == null) value else running + value
                seenAny = true
                continue
            }

            val asUnit = UNITS[token]
            if (asUnit != null) {
                var value = asUnit
                val pending = modifier
                if (pending != null) {
                    value += pending
                    modifier = null
                }
                val running = current
                current = if (running == null) value else running + value
                seenAny = true
                continue
            }

            val mult = MULTIPLIERS[token]
            if (mult != null) {
                var base = current
                if (base == null) {
                    // "sava sau" / "paune sau" -- the modifier applies to an
                    // implied 1, giving 125 and 75 respectively.
                    base = 1.0
                    val pending = modifier
                    if (pending != null) {
                        base += pending
                        modifier = null
                    }
                }
                if (mult >= 1000) {
                    total += base * mult
                    current = null
                } else {
                    current = base * mult
                }
                seenAny = true
                continue
            }

            // Any other word ends the number phrase.
            break
        }

        if (!seenAny) return null

        // A dangling modifier with nothing after it ("sava" alone) means 1.25.
        val trailing = modifier
        if (current == null && trailing != null) current = 1.0 + trailing

        return total + (current ?: 0.0)
    }

    /** A number phrase found in free text: its value and the text it matched. */
    data class Found(val value: Double, val matched: String)

    /**
     * Find every number phrase in free text, in the order they appear.
     */
    fun findNumbers(text: String): List<Found> {
        val raw = tokenize(text)

        // Neutralise weak number words used as verbs before scanning, so they
        // break runs exactly like a clause boundary would.
        val tokens = raw.mapIndexed { i, t -> if (isVerbNotNumber(raw, i)) BOUNDARY else t }

        val found = mutableListOf<Found>()
        var i = 0
        while (i < tokens.size) {
            if (!isNumberToken(tokens[i])) {
                i += 1
                continue
            }

            // Greedily take the longest run of number tokens, allowing fillers
            // in the middle but never at the end.
            var j = i
            var end = i
            while (j < tokens.size) {
                if (isNumberToken(tokens[j])) {
                    j += 1
                    end = j
                } else if (tokens[j] in FILLERS && j + 1 < tokens.size &&
                    isNumberToken(tokens[j + 1])
                ) {
                    j += 1
                } else {
                    break
                }
            }

            val run = tokens.subList(i, end)
            parseNumberPhrase(run)?.let { found.add(Found(it, run.joinToString(" "))) }
            i = maxOf(end, i + 1)
        }
        return found
    }

    /** Convenience: the first number found in the text, if any. */
    fun parseNumber(text: String): Double? = findNumbers(text).firstOrNull()?.value
}
