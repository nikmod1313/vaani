package app.vaaniledger.extract

import app.vaaniledger.data.ShopCategory

/**
 * The deterministic extraction layer, on-device.
 *
 * A Kotlin port of `backend/app/extraction/rules.py`. Resolves the amount,
 * whether it was cash / UPI / udhaar, which items and how many, and whether
 * the message is a sale or a payment coming back -- all without a network
 * call, which is the point: this app is used on a shop counter, and a
 * pipeline that needs the network is one that dies there.
 *
 * The Python copy remains the reference implementation with the full test
 * suite. Every trap it documents is reproduced here deliberately, because
 * each one was a wrong number in someone's ledger:
 *
 *   - "do" means 2 but usually "send" -- handled in [NumberParser].
 *   - A quantity is not a price. A missing amount is recoverable; a wrong
 *     one is not.
 *   - `\b` does not work on Devanagari, in Python OR Java, because both
 *     define it against `\w` and neither counts matras as word characters.
 *     Use [DEVA_START] / [DEVA_END] instead.
 *   - A rate is never inferred by division.
 */
object RulesEngine {

    enum class PaymentMode(val wire: String) {
        CASH("cash"), UPI("upi"), UDHAAR("udhaar"), UNKNOWN("unknown")
    }

    enum class Direction(val wire: String) {
        SALE("sale"), PAYMENT("payment"), UNKNOWN("unknown")
    }

    // ----------------------------------------------------------------------
    // Lexicons
    // ----------------------------------------------------------------------

    private val MODE_WORDS: Map<String, PaymentMode> = mapOf(
        // Cash
        "nakad" to PaymentMode.CASH, "nagad" to PaymentMode.CASH,
        "cash" to PaymentMode.CASH, "rokad" to PaymentMode.CASH,
        "नकद" to PaymentMode.CASH, "नगद" to PaymentMode.CASH,
        // UPI and its brand names -- shopkeepers say the app, not the protocol
        "upi" to PaymentMode.UPI, "phonepe" to PaymentMode.UPI,
        "gpay" to PaymentMode.UPI, "googlepay" to PaymentMode.UPI,
        "paytm" to PaymentMode.UPI, "bhim" to PaymentMode.UPI,
        "online" to PaymentMode.UPI, "scan" to PaymentMode.UPI,
        "qr" to PaymentMode.UPI, "transfer" to PaymentMode.UPI,
        "यूपीआई" to PaymentMode.UPI, "ऑनलाइन" to PaymentMode.UPI,
        // Credit
        "udhaar" to PaymentMode.UDHAAR, "udhar" to PaymentMode.UDHAAR,
        "udhaari" to PaymentMode.UDHAAR, "baaki" to PaymentMode.UDHAAR,
        "baki" to PaymentMode.UDHAAR, "credit" to PaymentMode.UDHAAR,
        "khaate" to PaymentMode.UDHAAR, "khate" to PaymentMode.UDHAAR,
        "उधार" to PaymentMode.UDHAAR, "बाकी" to PaymentMode.UDHAAR,
    )

    // Devanagari-safe word boundaries.
    //
    // `\b` is defined against `\w`, and neither Python nor Java counts
    // Devanagari vowel signs (matras) as word characters -- they are
    // nonspacing marks. So a trailing `\b` NEVER matches after a word ending
    // in a matra, which is most Hindi words. Whitespace boundaries mean what
    // we actually want and work in both scripts.
    private const val DEVA_START = """(?<!\S)"""
    private const val DEVA_END = """(?!\S)"""

    /** Phrases meaning "write it down" -> credit, even without the word udhaar. */
    private val CREDIT_PHRASES = listOf(
        """\blikh\s+(?:lena|lo|dena|do|dijiye)\b""",
        """\bkhaate?\s+mein\b""",
        DEVA_START + """लिख\s+(?:लेना|लो|देना|दो)""" + DEVA_END,
        DEVA_START + """खाते\s+में""" + DEVA_END,
    ).map { Regex(it) }

    // Deferred payment is credit, even when nobody says "udhaar".
    //
    // "Paise kal doonga" is how a regular customer actually opens a credit
    // line -- the word udhaar never appears because both sides already know
    // that is what this is.
    private val DEFERRED_PHRASES = listOf(
        """\bpaise?\b.{0,20}?\b(?:kal|baad|shaam|raat|sunday|somvar)\b""",
        """\b(?:kal|baad\s+me|baad\s+mein|shaam\s+ko|raat\s+ko)\b.{0,15}?\b(?:doonga|dunga|dedunga|de\s+dunga|dungi)\b""",
        """\b(?:doonga|dunga|dungi|dedunga)\b""",
        """\bhisab\b.{0,15}?\b(?:kar\s+lenge|kar\s+lena|baad|end)\b""",
        """\bmahine?\s+ke\s+(?:end|akhir|ant)\b""",
        """\bbaad\s+m[ei]in?\s+(?:de|dunga|doonga|karenge)\w*\b""",
        DEVA_START + """पैसे?\s+कल""" + DEVA_END,
        DEVA_START + """बाद\s+में\s+(?:दूंगा|दे)""" + DEVA_END,
    ).map { Regex(it) }

    // Phrases meaning money came back in.
    //
    // Note `diy[ae]` rather than `diye?`. The latter matches "diy" plus an
    // optional "e", so the trailing \b never lands on "diya" -- and "kar
    // diya", "de diya" and "bhej diya" are how people actually write it.
    // Every payment phrased in the past tense was being read as a sale.
    private val PAYMENT_PHRASES = listOf(
        """\bpayment\b.{0,20}?\b(?:mila|mile|aaya|aa\s+gaya|hua|kiya|kar\s+diy[ae])\b""",
        """\bpaise?\b.{0,15}?\b(?:mil|aa|de|bhej|diy)\w*\b""",
        """\bmil\s+gaye?\b""",
        """\bjama\s+kar\w*\b""",
        """\bchukta\b""",
        """\bsettle\w*\b""",
        """\b(?:account|khata|hisab|hisaab)\b.{0,15}?\bclear\b""",
        """\bclear\s+kar\s+diy[ae]\b""",
        """\b(?:de|bhej|kar|jama)\s+diy[ae]\b""",
        """\bbheja\b""",
        """\bbheje\b""",
        """\b(?:nakad|nagad|cash|rokad|upi|online)\s+diy[ae]\b""",
        """\b(?:phonepe|gpay|googlepay|paytm|bhim|upi|online|scan|transfer)\b.{0,25}?\b(?:kar\s+diy[ae]|bhej\s+diy[ae]|diy[ae]|kiya|bheja|bheje|mila|mile)\b""",
        DEVA_START + """पैसे?\s*(?:मिल|आ|दे)\w*""",
        DEVA_START + """जमा""" + DEVA_END,
        DEVA_START + """(?:भेज|कर|दे)\s*(?:दिया|दिये|दिए)""" + DEVA_END,
        DEVA_START + """मिल(?:े|गया|गए)""" + DEVA_END,
        DEVA_START + """भेजे""" + DEVA_END,
    ).map { Regex(it) }

    private val UNITS: Map<String, String> = mapOf(
        "kilo" to "kg", "kg" to "kg", "kilos" to "kg", "किलो" to "kg",
        "gram" to "g", "gm" to "g", "g" to "g", "ग्राम" to "g",
        "litre" to "l", "liter" to "l", "ltr" to "l", "l" to "l", "लीटर" to "l",
        "ml" to "ml",
        "packet" to "packet", "pkt" to "packet", "पैकेट" to "packet",
        "dozen" to "dozen", "darjan" to "dozen", "दर्जन" to "dozen",
        "piece" to "pc", "pieces" to "pc", "pcs" to "pc", "pc" to "pc",
        "nag" to "pc", "अदद" to "pc",
        "bottle" to "bottle", "botal" to "bottle", "बोतल" to "bottle",
        "bora" to "sack", "sack" to "sack", "bori" to "sack",
        "pav" to "pav", "adha" to "half",
    )

    // Item words that are also everyday verbs. Same trap as "do" in the
    // number parser: "khaate mein daal do" is not two kilos of lentils.
    // These only count as items when a quantity or unit sits next to them.
    private val WEAK_ITEMS = setOf("dal", "daal", "दाल", "aata")

    private val CURRENCY = Regex("""(?:₹|rs\.?|rupaye|rupees|रु\.?|रुपये)""", RegexOption.IGNORE_CASE)

    // Tokens that mark the number before them as MONEY rather than a count.
    //
    // "ka / ki / ke" is the Hindi possessive: "paune sau ka chai" is
    // seventy-five rupees WORTH of tea, not seventy-five teas.
    /** The shared unit table merged with a category's own. */
    internal fun unitTable(category: ShopCategory): Map<String, String> =
        UNITS + CategoryLexicon.unitsFor(category)

    private val PRICE_MARKERS = setOf(
        "ka", "ki", "ke", "का", "की", "के",
        "rupaye", "rupees", "rupay", "rs", "₹", "रुपये", "रुपए",
    )

    // Words meaning the number beside them is a PER-UNIT rate, not a total.
    //
    // Split by how much they commit to. "each" and "per" can only ever mean a
    // rate. The possessives mean a rate OR a total depending on what follows
    // -- "500 ka kurti" is a rate, "dhai sau ka udhaar" is the whole bill --
    // so only those need the mode-word guard in [trailingRate].
    private val EXPLICIT_RATE_MARKERS =
        setOf("each", "per", "prati", "प्रति", "rate", "hisab", "हिसाब")
    private val AMBIGUOUS_RATE_MARKERS =
        setOf("ka", "ke", "ki", "का", "के", "की", "wala", "wali", "वाला", "वाली")
    private val RATE_MARKERS = EXPLICIT_RATE_MARKERS + AMBIGUOUS_RATE_MARKERS

    // ----------------------------------------------------------------------
    // Result types
    // ----------------------------------------------------------------------

    data class LineItem(
        val name: String,
        val quantity: Double? = null,
        val unit: String? = null,
        /** The adjective the customer used -- "blue" in "blue kurti". */
        val modifier: String? = null,
        /**
         * Per-unit price, only when the message STATED one. Never inferred
         * from the total -- dividing an amount by a quantity invents a rate
         * nobody agreed to.
         */
        val unitPrice: Double? = null,
    ) {
        val displayName: String
            get() = if (modifier != null) "$modifier $name".trim() else name

        /** Extended price, only when both sides were actually stated. */
        val lineTotal: Double?
            get() = if (unitPrice != null && quantity != null) {
                Math.round(unitPrice * quantity * 100.0) / 100.0
            } else null

        fun label(): String {
            if (quantity == null) return displayName
            val qty = if (quantity == Math.floor(quantity)) quantity.toInt().toString()
            else quantity.toString()
            return "$displayName $qty${unit ?: ""}".trim()
        }
    }

    data class RuleResult(
        val amount: Double? = null,
        val amountSource: String? = null,
        val mode: PaymentMode = PaymentMode.UNKNOWN,
        val direction: Direction = Direction.UNKNOWN,
        val items: List<LineItem> = emptyList(),
        val allNumbers: List<NumberParser.Found> = emptyList(),
        /** Which fields the rules resolved on their own. */
        val resolved: Set<String> = emptySet(),
    )

    // ----------------------------------------------------------------------
    // Detection
    // ----------------------------------------------------------------------

    /** Payment mode plus the word that decided it. */
    fun detectMode(text: String): Pair<PaymentMode, String?> {
        val norm = NumberParser.normalise(text)
        val hits = mutableListOf<Pair<PaymentMode, String>>()
        for (token in norm.split(Regex("""\s+"""))) {
            val clean = token.trim('.')
            MODE_WORDS[clean]?.let { hits.add(it to clean) }
        }

        if (hits.isNotEmpty()) {
            // You cannot pay WITH udhaar: in "wo 250 wala udhaar clear kar
            // diya, gpay dekh lena" the word udhaar names the debt being
            // settled, while gpay is how it was settled -- so on a payment
            // the instrument wins over the debt type.
            val instruments = hits.filter {
                it.first == PaymentMode.CASH || it.first == PaymentMode.UPI
            }
            if (instruments.isNotEmpty() && detectDirection(text) == Direction.PAYMENT) {
                return instruments.first()
            }
            return hits.first()
        }

        for (pattern in CREDIT_PHRASES + DEFERRED_PHRASES) {
            pattern.find(norm)?.let { return PaymentMode.UDHAAR to it.value }
        }
        return PaymentMode.UNKNOWN to null
    }

    /** Is this goods going out, or money coming back? */
    fun detectDirection(
        text: String,
        category: ShopCategory = ShopCategory.GENERAL,
    ): Direction {
        val norm = NumberParser.normalise(text)
        if (PAYMENT_PHRASES.any { it.containsMatchIn(norm) }) return Direction.PAYMENT
        // Items present means goods moved.
        if (extractItems(text, category).isNotEmpty()) return Direction.SALE
        return Direction.UNKNOWN
    }

    /**
     * Resolve a token to an item noun, allowing an English plural.
     *
     * Customers write "blue kurtis" and "2 sarees"; a lexicon holding only
     * the singular sees neither, and the item -- the whole subject of the
     * order -- is silently dropped. Returns the SINGULAR so the khata reads
     * consistently however the customer typed it. Plurals already in a
     * lexicon (jeans, socks, drops) match on the first check and are never
     * stripped.
     */
    private fun matchItem(token: String, lexicon: Set<String>): String? {
        if (token in lexicon) return token
        if (token.endsWith("es") && token.dropLast(2) in lexicon) return token.dropLast(2)
        if (token.endsWith("s") && token.dropLast(1) in lexicon) return token.dropLast(1)
        return null
    }

    /**
     * A per-unit rate stated after an item: "kurti 500 ka", "2 saree chahiye
     * 800 each".
     *
     * How far ahead this looks depends on how much the marker commits to. An
     * AMBIGUOUS possessive is only read as a rate immediately after the item,
     * because the same shape carries the total far more often: in "2 kilo
     * chawal, dhai sau ka udhaar" the number is the whole bill. An EXPLICIT
     * marker cannot mean anything but a rate, so it is searched for across
     * the rest of the clause -- "2 saree chahiye 800 each" puts a verb
     * between the item and its price.
     *
     * The scan stops at a clause boundary or the next item noun, so a rate
     * belongs to one item and never leaks onto the one after it.
     */
    private fun trailingRate(
        tokens: List<String>,
        start: Int,
        lexicon: Set<String>,
    ): Double? {
        fun numberRun(from: Int): Pair<Double?, Int> {
            var j = from
            while (j < tokens.size && NumberParser.isNumberToken(tokens[j].trim('.'))) j++
            if (j == from) return null to from
            return NumberParser.parseNumberPhrase(tokens.subList(from, j)) to j
        }

        // --- adjacent, any marker -----------------------------------------
        if (start < tokens.size && NumberParser.isNumberToken(tokens[start].trim('.'))) {
            val (value, j) = numberRun(start)
            if (value != null && j < tokens.size) {
                val marker = tokens[j].trim('.')
                val after = if (j + 1 < tokens.size) tokens[j + 1].trim('.') else ""
                val blockedByMode = marker in AMBIGUOUS_RATE_MARKERS && after in MODE_WORDS
                if (marker in RATE_MARKERS && !blockedByMode && value > 0) return value
            }
        }

        // --- anywhere in the clause, explicit markers only ------------------
        var i = start
        while (i < tokens.size) {
            val token = tokens[i].trim('.')
            if (token == NumberParser.BOUNDARY) break
            if (i > start && matchItem(token, lexicon) != null) break // belongs to that item
            if (NumberParser.isNumberToken(token)) {
                val (value, j) = numberRun(i)
                if (value != null && value > 0 && j < tokens.size &&
                    tokens[j].trim('.') in EXPLICIT_RATE_MARKERS
                ) {
                    return value
                }
                i = maxOf(j, i + 1)
                continue
            }
            i++
        }
        return null
    }

    /**
     * Pull item / quantity / unit / rate tuples.
     *
     * Walks the token stream tracking the most recent number and unit, so
     * "2 kilo chawal" and "chawal 2 kilo" both resolve.
     */
    fun extractItems(
        text: String,
        category: ShopCategory = ShopCategory.GENERAL,
    ): List<LineItem> {
        val lexicon = CategoryLexicon.itemsFor(category)
        val modifiers = CategoryLexicon.modifiersFor(category)
        val units = UNITS + CategoryLexicon.unitsFor(category)

        val tokens = NumberParser.tokenize(text)
        val items = mutableListOf<LineItem>()
        var pendingQty: Double? = null
        var pendingUnit: String? = null
        // True when pendingQty arrived with a unit ("2kg"), making it a
        // measure a later bare number must not overwrite.
        var pendingMeasured = false
        var pendingModifier: String? = null

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i].trim('.')

            if (token == NumberParser.BOUNDARY) {
                pendingQty = null; pendingUnit = null; pendingModifier = null
                pendingMeasured = false
                i++
                continue
            }

            // Collect a number run.
            if (NumberParser.isNumberToken(token)) {
                var j = i
                while (j < tokens.size && NumberParser.isNumberToken(tokens[j].trim('.'))) j++
                val value = NumberParser.parseNumberPhrase(tokens.subList(i, j))
                val next = if (j < tokens.size) tokens[j].trim('.') else ""

                // A number followed by a unit is a MEASURE -- "2kg" is two
                // kilos, and nothing later in the sentence can turn it into
                // something else.
                val measured = next in units

                // "paune sau ka chai" -- a price, not a count. Leaving
                // pendingQty unset keeps the number available as the amount.
                if (value != null && next !in PRICE_MARKERS) {
                    if (pendingQty != null && pendingMeasured && !measured) {
                        // A BARE number after an established measure is a
                        // price, not a replacement count. Real chat states
                        // them in this order, often across separate messages:
                        //
                        //     "2kg for"  "500 only"  "organic"  "tamatar"
                        //
                        // Overwriting booked FIVE HUNDRED KILOS of tomatoes,
                        // because the unit from the measure survived next to
                        // the new number. Mirrors rules.py exactly.
                    } else {
                        pendingQty = value
                        pendingMeasured = measured
                        // A bare number carries no unit of its own.
                        if (!measured) pendingUnit = null
                    }
                }
                i = j
                continue
            }

            if (token in units) {
                pendingUnit = units[token]
                i++
                continue
            }

            // An adjective in front of a noun belongs to it: "blue kurti" is
            // one item. Held rather than emitted, because a modifier with no
            // noun after it ("blue hai kya") is not an order for anything.
            if (token in modifiers) {
                pendingModifier = token
                i++
                continue
            }

            val matched = matchItem(token, lexicon)
            if (matched != null) {
                val next = if (i + 1 < tokens.size) tokens[i + 1].trim('.') else ""
                // A digit or a unit counts as corroboration; a weak number
                // word does not -- "daal do" is "put it in", not two kilos.
                val corroborated = next in units ||
                    (NumberParser.isNumberToken(next) && next != "do" && next != "de")

                if (matched in WEAK_ITEMS && pendingQty == null && pendingUnit == null &&
                    !corroborated
                ) {
                    i++
                    continue
                }

                var quantity = pendingQty
                var unit = pendingUnit
                val modifier = pendingModifier
                pendingQty = null; pendingUnit = null; pendingModifier = null
                pendingMeasured = false
                i++

                // Trailing quantity: shopkeepers write both "2 kilo chawal"
                // and "chawal 2 kilo". Without this the trailing quantity
                // drifts onto the NEXT item and the orphaned number gets
                // picked as the amount -- "dal 2 kilo aur atta 5 kilo" booked
                // Rs 5. A trailing number counts as a quantity ONLY when a
                // unit follows it: "chawal 300" is three hundred rupees of
                // rice, and swallowing that as a quantity loses the amount.
                if (quantity == null && i < tokens.size) {
                    val lookahead = tokens[i].trim('.')
                    if (NumberParser.isNumberToken(lookahead) &&
                        lookahead != "do" && lookahead != "de"
                    ) {
                        var j = i
                        while (j < tokens.size &&
                            NumberParser.isNumberToken(tokens[j].trim('.'))
                        ) j++
                        val followedByUnit = j < tokens.size && tokens[j].trim('.') in units
                        val value = if (followedByUnit) {
                            NumberParser.parseNumberPhrase(tokens.subList(i, j))
                        } else null
                        if (value != null) {
                            quantity = value
                            unit = units[tokens[j].trim('.')]
                            i = j + 1
                        }
                    }
                }

                items.add(
                    LineItem(
                        name = matched,
                        quantity = quantity,
                        unit = unit,
                        modifier = modifier,
                        unitPrice = trailingRate(tokens, i, lexicon),
                    )
                )
                continue
            }

            i++
        }

        return items
    }

    /** Every number immediately followed by a unit. These are never money. */
    private fun measureValues(text: String, category: ShopCategory): Set<Double> {
        val units = UNITS + CategoryLexicon.unitsFor(category)
        val tokens = NumberParser.tokenize(text)
        val found = mutableSetOf<Double>()
        var i = 0
        while (i < tokens.size) {
            if (!NumberParser.isNumberToken(tokens[i].trim('.'))) {
                i++
                continue
            }
            var j = i
            while (j < tokens.size && NumberParser.isNumberToken(tokens[j].trim('.'))) j++
            if (j < tokens.size && tokens[j].trim('.') in units) {
                NumberParser.parseNumberPhrase(tokens.subList(i, j))?.let { found.add(it) }
            }
            i = maxOf(j, i + 1)
        }
        return found
    }

    /**
     * Choose which number in the message is the money.
     *
     * Preference order: a number adjacent to a currency marker, then one
     * adjacent to a payment-mode word, then the largest number not already
     * consumed as an item quantity.
     */
    private fun pickAmount(
        text: String,
        numbers: List<NumberParser.Found>,
        items: List<LineItem>,
        category: ShopCategory,
    ): Pair<Double?, String?> {
        if (numbers.isEmpty()) return null to null

        val norm = NumberParser.normalise(text)

        // 1. Currency-adjacent.
        for ((value, matched) in numbers) {
            val quoted = Regex.escape(matched)
            val patterns = listOf(
                Regex("${CURRENCY.pattern}\\s*$quoted", RegexOption.IGNORE_CASE),
                Regex("$quoted\\s*${CURRENCY.pattern}", RegexOption.IGNORE_CASE),
            )
            if (patterns.any { it.containsMatchIn(norm) }) return value to "currency-adjacent"
        }

        // 2. Mode-adjacent -- "dhai sau ka udhaar".
        val modeWords = MODE_WORDS.keys.joinToString("|") { Regex.escape(it) }
        for ((value, matched) in numbers) {
            val window = Regex("${Regex.escape(matched)}\\s+(?:\\w+\\s+){0,2}(?:$modeWords)")
            if (window.containsMatchIn(norm)) return value to "mode-adjacent"
        }

        // 3. Largest number not already consumed as an item quantity.
        //
        // A number directly followed by a unit is a measure, full stop --
        // even when a clause boundary stopped it attaching to its item. In
        // "chai khatam ho gayi, 2 packet bhej do jaldi" the 2 never reaches
        // chai, and without this it gets picked as the amount: a Rs 2 sale.
        val used = buildSet {
            items.mapNotNull { it.quantity }.forEach { add(it) }
            items.mapNotNull { it.unitPrice }.forEach { add(it) }
            addAll(measureValues(text, category))
        }
        val spare = numbers.filter { it.value !in used }
        if (spare.isNotEmpty()) {
            return spare.maxByOrNull { it.value }!!.value to "largest-unused"
        }

        // Every number was a quantity, so no price was stated. Falling
        // through to "largest overall" here would reuse a quantity AS the
        // amount: "2 kilo chawal bhej do" booked Rs 2. A missing amount is
        // recoverable -- the confirm sheet asks for it -- but a wrong one is
        // money out of someone's pocket.
        if (items.isNotEmpty()) return null to null

        return numbers.maxByOrNull { it.value }!!.value to "largest-overall"
    }

    /** Run the whole deterministic layer over one message. */
    fun applyRules(
        text: String,
        category: ShopCategory = ShopCategory.GENERAL,
    ): RuleResult {
        val resolved = mutableSetOf<String>()

        val allNumbers = NumberParser.findNumbers(text)
        val items = extractItems(text, category)
        if (items.isNotEmpty()) resolved.add("items")

        val (mode, _) = detectMode(text)
        if (mode != PaymentMode.UNKNOWN) resolved.add("mode")

        val direction = detectDirection(text, category)
        if (direction != Direction.UNKNOWN) resolved.add("direction")

        val (amount, source) = pickAmount(text, allNumbers, items, category)

        // A stated rate times a stated quantity beats every heuristic below
        // it: both halves came from the message, so the total is arithmetic
        // rather than a guess. "3 kurti 500 each" is 1500, and no number in
        // the text says so.
        val lineTotals = items.mapNotNull { it.lineTotal }
        if (lineTotals.isNotEmpty()) {
            resolved.add("amount")
            return RuleResult(
                amount = Math.round(lineTotals.sum() * 100.0) / 100.0,
                amountSource = "line-totals",
                mode = mode,
                direction = direction,
                items = items,
                allNumbers = allNumbers,
                resolved = resolved,
            )
        }

        // "largest-unused" is a guess, not a resolution -- leave it for the
        // user to confirm rather than claiming certainty we do not have.
        if (amount != null && (source == "currency-adjacent" || source == "mode-adjacent")) {
            resolved.add("amount")
        }

        return RuleResult(
            amount = amount,
            amountSource = source,
            mode = mode,
            direction = direction,
            items = items,
            allNumbers = allNumbers,
            resolved = resolved,
        )
    }
}
