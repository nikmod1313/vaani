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
        // Cash on delivery is how an online-style order says "cash".
        "cod" to PaymentMode.CASH,
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
        """\blikh\s+(?:lena|lo|dena|do|dijiye|lijiye|lijie)\b""",
        // "khate me" is written at least as often as "khate mein".
        """\bkhaate?\s+m(?:ein|e|ai|ain)\b""",
        """\b(?:hisaa?b|account|khaate?)\s+m(?:ein|e)\s+(?:jod|likh|daal|dal|add)\w*""",
        """\bjod\s+(?:do|dena|dijiye|lena)\b""",
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
    /**
     * Future intent, which is NOT a payment.
     *
     * "I will pay with upi" says how a SALE will be settled. Reading it as
     * money already received clears a debt nobody has paid, and the
     * shopkeeper never chases it. Checked first, and it wins: a missed
     * payment is noticed the next time the khata is read, an invented one is
     * not.
     */
    private val FUTURE_PAYMENT_PHRASES = listOf(
        """\b(?:i\s+)?will\s+pay\b""", """\bi'?ll\s+pay\b""", """\bgoing\s+to\s+pay\b""",
        """\bpay\s+(?:you\s+)?(?:later|tomorrow|soon|next)\b""",
        """\bpayment\s+(?:later|tomorrow|soon)\b""",
        """\bwill\s+(?:make|send|do)\s+(?:the\s+)?payment\b""",
        """\bpayment\s+will\b""",
        """\bkal\s+(?:de|kar)\w*\b""", """\bbaad\s+m[ei]in?\b""",
    ).map { Regex(it) }

    private val PAYMENT_PHRASES = listOf(
        // English, PAST tense only -- see FUTURE_PAYMENT_PHRASES above. These
        // were missing entirely, so "i have completed the payment" left a
        // settled bill sitting in the khata as outstanding.
        """\bi\s+(?:have\s+)?paid\b""",
        """\bhave\s+paid\b""",
        """\b(?:have\s+)?(?:completed|made|done)\s+the\s+payment\b""",
        """\bpayment\s+(?:is\s+)?(?:done|complete|completed|received|sent|made)\b""",
        """\bdone\s+(?:the\s+)?payment\b""",
        """\b(?:transferred|sent)\s+(?:the\s+)?(?:money|amount|payment|cash)\b""",
        """\bpaid\s+(?:you|it|already|for\s+it)\b""",
        """\bpayment\s+ho\s+gay[ai]\b""",
        """\bpayment\b.{0,20}?\b(?:mila|mile|aaya|aa\s+gaya|hua|kiya|kar\s+diy[ae]|kar\s+d(?:i|ii|ia))\b""",
        // A bare "paid" -- the whole message -- after a reminder.
        """^\W*paid\W*$""",
        // ...or with its figure: "2000 paid", "paid 2000". Mirrors rules.py.
        """^\W*(?:rs\.?|₹)?\s*\d[\d,]*(?:\.\d+)?\s*(?:rs|rupees|rupaye)?\s+paid\W*$""",
        """^\W*paid\s+(?:rs\.?|₹)?\s*\d[\d,]*(?:\.\d+)?\W*$""",
        """\bpaise?\b.{0,15}?\b(?:mil|aa|de|bhej|diy)\w*\b""",
        """\bmil\s+gaye?\b""",
        """\bjama\s+kar\w*\b""",
        """\bchukta\b""",
        """\bsettle\w*\b""",
        """\b(?:account|khata|hisab|hisaab)\b.{0,15}?\bclear\b""",
        """\bclear\s+kar\s+diy[ae]\b""",
        """\b(?:nakad|nagad|cash|rokad|upi|online)\s+diy[ae]\b""",
        // A payment app plus any completed action, in either order.
        """\b(?:phonepe|gpay|googlepay|paytm|bhim|upi|online|scan|transfer)\b.{0,25}?\b(?:kar\s+diy[ae]|bhej\s+diy[ae]|diy[ae]|kiya|kia|bheja|bheje|mila|mile|done|ho\s+gaya)\b""",
        """\b(?:kar\s+diy[ae]|bhej\s+diy[ae]|bheja|bheje|kiya|kia|sent|paid|transferred)\b.{0,25}?\b(?:phonepe|gpay|googlepay|paytm|bhim|upi|online)\b""",
        DEVA_START + """पैसे?\s*(?:मिल|आ|दे)\w*""",
        DEVA_START + """जमा""" + DEVA_END,
        DEVA_START + """(?:भेज|कर|दे)\s*(?:दिया|दिये|दिए)""" + DEVA_END,
        DEVA_START + """मिल(?:े|गया|गए)""" + DEVA_END,
        DEVA_START + """भेजे""" + DEVA_END,
    ).map { Regex(it) }

    /**
     * Generic past-tense giving and sending. "bhej diya" is how most payments
     * are written -- and also how a complaint about goods is: "kal 3 kg aata
     * bheja tha, kharab tha" invented a payment out of a bad bag of flour. So
     * these count only when the message is ABOUT money. Mirrors rules.py.
     */
    private val MONEY_GATED_PAYMENT_PHRASES = listOf(
        """\b(?:de|bhej|kar|jama)\s+diy[ae]\b""",
        """\bbheja\b""", """\bbheje\b""",
        // "sent 2250 to your account using upi" -- gated, since "sent the
        // parcel" is not money.
        """\b(?:sent|transferred)\b""",
    ).map { Regex(it) }

    private val MONEY_WORDS = Regex(
        """\b(?:paise?|paisa|pese|payment|amount|rupaye|rupay|rupees|rs|cash|nakad|nagad|""" +
            """upi|online|gpay|phonepe|paytm)\b|₹|रुपये|रुपए|पैसे?"""
    )

    /**
     * A payment that SETTLES something names the debt. Without one of these,
     * goods with quantities said to be paid for are a sale paid on the spot
     * -- "3 sabun 120 ka UPI kar diya" -- not a balance being cleared.
     */
    private val SETTLEMENT_WORDS = Regex(
        """\b(?:paise?|paisa|pese|baaki|baki|udhaa?r|hisaa?b|pichl\w*|purana|purane|""" +
            """due|balance|khaate?|bakaya)\b|पैसे?|बाकी|उधार|हिसाब"""
    )

    private val CASH_PHRASES = Regex(
        """\b\d+\s+(?:ka|ke|wala|wale|rupaye\s+ka)\s+note\b|\bnote\s+(?:de|diya|bhej)\w*""" +
            """|\bchh?utta\b|\b(?:baaki|baki|bacha|bache|change)\b.{0,12}?\b(?:wapa?s|lauta\w*|return)\b"""
    )

    /** Asking for money BACK -- neither a payment nor a sale. Mirrors `_REFUND`. */
    private val REFUND = Regex(
        """\brefund\w*\b|\bwapa?s\s+(?:kar|de|bhej|karo|dena|kijiye)\w*|\b(?:paid|payment)\s+twice\b""" +
            """|\b(?:double|do\s+baar|2\s+baar|dobara)\s+(?:payment|pay|gpay|paytm|phonepe|upi|ho\s+gaya)\b""" +
            """|\bgalti\s+se\b.{0,30}?\b(?:payment|bhej|pay|gpay|paytm|phonepe)\w*""" +
            """|(?<!\S)वापस\s+(?:कर|दे|भेज)"""
    )

    private val UNITS: Map<String, String> = mapOf(
        "kilo" to "kg", "kg" to "kg", "kilos" to "kg", "kgs" to "kg",
        "kilogram" to "kg", "किलो" to "kg",
        "gram" to "g", "grams" to "g", "gm" to "g", "gms" to "g", "g" to "g", "ग्राम" to "g",
        "litre" to "l", "liter" to "l", "litres" to "l", "liters" to "l",
        "ltr" to "l", "ltrs" to "l", "lt" to "l", "l" to "l", "लीटर" to "l",
        "ml" to "ml",
        "packet" to "packet", "pkt" to "packet", "pkts" to "packet", "pack" to "packet",
        "packs" to "packet", "packets" to "packet", "पैकेट" to "packet", "pouch" to "packet",
        "tray" to "tray", "trays" to "tray", "ट्रे" to "tray",
        "box" to "box", "dabba" to "box", "dabbe" to "box", "डिब्बा" to "box", "डिब्बे" to "box",
        "jar" to "jar", "tin" to "tin", "roll" to "roll", "bundle" to "bundle",
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

    private val CURRENCY = Regex(
        """(?:₹|rs\.?|rupaye|rupees|rupay|rupye|rupya|rupiya|रु\.?|रुपये|रुपए|रूपये)""",
        RegexOption.IGNORE_CASE,
    )

    // Tokens that mark the number before them as MONEY rather than a count.
    //
    // "ka / ki / ke" is the Hindi possessive: "paune sau ka chai" is
    // seventy-five rupees WORTH of tea, not seventy-five teas.
    /**
     * A typed item, put in the "<name> <qty><unit>" shape every label has.
     * Mirrors `normalise_label` in rules.py.
     *
     * The edit sheet takes items as free text, and "do kurti" typed there was
     * stored whole -- a product called "do kurti", quantity one. A leading
     * count is lifted off and written the way [LineItem.label] writes it:
     * "do kurti" -> "kurti 2", "2 kg chawal" -> "chawal 2kg". A digit is only
     * taken as a count when a unit or a known item follows it, so a product
     * whose name starts with a number ("5 star") is left alone.
     */
    fun normaliseLabel(label: String): String {
        val raw = label.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
        val tokens = raw.split(" ")
        if (tokens.size < 2) return raw
        val lower = tokens.map { it.lowercase().trim('.') }
        var j = 0
        while (j < tokens.size - 1 && NumberParser.isNumberToken(lower[j])) j++
        if (j == 0 || NumberParser.isNumberToken(lower[j])) return raw
        val qty = NumberParser.parseNumberPhrase(lower.subList(0, j)) ?: return raw
        val unit = if (j < tokens.size - 1) UNITS[lower[j]] else null
        val rest = if (unit != null) tokens.drop(j + 1) else tokens.drop(j)
        if (qty <= 0 || qty >= 1000 || rest.isEmpty()) return raw
        val worded = lower.subList(0, j).none { w -> w.any { it.isDigit() } }
        if (!(worded || unit != null || matchItem(rest.last().lowercase(), CategoryLexicon.allKnownItems()) != null)) return raw
        val shown = if (qty == Math.floor(qty)) qty.toLong().toString() else qty.toString()
        return "${rest.joinToString(" ")} $shown${unit ?: ""}"
    }

    /** The shared unit table merged with a category's own. */
    internal fun unitTable(category: ShopCategory): Map<String, String> =
        UNITS + CategoryLexicon.unitsFor(category)

    private val PRICE_MARKERS = setOf(
        "ka", "ki", "ke", "का", "की", "के",
        "rupaye", "rupees", "rupay", "rupye", "rupya", "rupiya", "rs", "₹", "रुपये", "रुपए",
    )

    /**
     * Joining words. An unattached measure does not survive one: in "2 coke 2
     * litre wali aur 1 lays" the "2 litre" describes the coke.
     */
    private val CONJUNCTIONS = setOf("aur", "and", "&", "+", "और", "n")

    private val POSSESSIVES = setOf("ka", "ki", "ke", "का", "की", "के")

    /**
     * Containers sold as things in their own right as well as used as
     * measures. Deliberately not "packet": "2 packet bhej do" is always a
     * measure of whatever was being discussed.
     */
    private val CONTAINERS = setOf("box", "dabba", "bottle", "botal", "bag", "बोतल", "डिब्बा")

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

        // Paying with a note is cash, and the "baaki" in "baaki lauta dena"
        // is the change coming back, not a debt.
        CASH_PHRASES.find(norm)?.let { return PaymentMode.CASH to it.value }

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

        // A promise to pay is not a payment. Checked first so no amount of
        // past-tense-looking wording after it can flip the reading.
        val promised = FUTURE_PAYMENT_PHRASES.any { it.containsMatchIn(norm) }
        // A request for a refund is neither a payment nor a sale.
        if (REFUND.containsMatchIn(norm)) return Direction.UNKNOWN
        val items = extractItems(text, category)

        var paid = false
        if (!promised) {
            paid = PAYMENT_PHRASES.any { it.containsMatchIn(norm) }
            if (!paid && aboutMoney(text, norm, items, category)) {
                paid = MONEY_GATED_PAYMENT_PHRASES.any { it.containsMatchIn(norm) }
            }
        }
        if (paid) {
            // Goods with quantities, paid for in the same breath, are a SALE
            // settled on the spot. Only a message that names a debt is a
            // payment.
            val counted = items.any { it.quantity != null }
            if (counted && !SETTLEMENT_WORDS.containsMatchIn(norm)) return Direction.SALE
            return Direction.PAYMENT
        }
        // Items present means goods moved.
        if (items.isNotEmpty()) return Direction.SALE
        return Direction.UNKNOWN
    }

    /** Does this message talk about money, rather than about goods? */
    private fun aboutMoney(
        text: String,
        norm: String,
        items: List<LineItem>,
        category: ShopCategory,
    ): Boolean {
        if (MONEY_WORDS.containsMatchIn(norm) || CURRENCY.containsMatchIn(norm)) return true
        val counts = items.mapNotNull { it.quantity }.toMutableSet()
        counts.addAll(measureValues(text, category))
        return NumberParser.findNumbers(text).any { it.value !in counts }
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
        // The Hindi oblique / plural: ande, kele, dabbe, kabze -- and in
        // Devanagari अंडे for अंडा. "ek darjan ande" named no item at all.
        if (token.endsWith("e") && token.dropLast(1) + "a" in lexicon) return token.dropLast(1) + "a"
        if (token.endsWith("े") && token.dropLast(1) + "ा" in lexicon) return token.dropLast(1) + "ा"
        // The Hindi feminine plural and oblique: kurtiyan / kurtiyaan /
        // kurtiyon for kurti, and कुर्तियाँ for कुर्ती. "do kurtiyan kharidi"
        // named no item at all, so the two was left with nothing to count.
        for (suffix in listOf("iyaan", "iyan", "iyon", "iya")) {
            if (token.endsWith(suffix) && token.dropLast(suffix.length) + "i" in lexicon) return token.dropLast(suffix.length) + "i"
        }
        for (suffix in listOf("ियाँ", "ियां", "ियों")) {
            if (token.endsWith(suffix) && token.dropLast(suffix.length) + "ी" in lexicon) return token.dropLast(suffix.length) + "ी"
        }
        // ...and the "-en" plural: chappalen, kitaben, किताबें.
        if (token.endsWith("en") && token.length > 4 && token.dropLast(2) in lexicon) return token.dropLast(2)
        if (token.endsWith("ें") && token.dropLast(2) in lexicon) return token.dropLast(2)
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
        fun numberRun(start: Int): Pair<Double?, Int> {
            // "bhej do 500 each": the "do" is "send it", not two. Read as part
            // of the run it made the rate 502 and the bill Rs 1004.
            var from = start
            while (from < tokens.size && NumberParser.isVerbNotNumber(tokens, from)) from++
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
            if (token == NumberParser.BOUNDARY) {
                // "2 kurti bhej do, 500 each": the rate sits in the next
                // clause. It is this item's only when nothing after it names
                // another one. Mirrors rules.py.
                if (tokens.drop(i + 1).any { matchItem(it.trim('.'), lexicon) != null }) break
                i++
                continue
            }
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
     * "2 kilo chawal" and "chawal 2 kilo" both resolve. A line-for-line port
     * of `extract_items` in rules.py; see there for the reasoning behind
     * each rule, which is restated here only briefly.
     */
    fun extractItems(
        text: String,
        category: ShopCategory = ShopCategory.GENERAL,
    ): List<LineItem> {
        val lexicon = CategoryLexicon.itemsFor(category)
        val modifiers = CategoryLexicon.modifiersFor(category)
        val units = UNITS + CategoryLexicon.unitsFor(category)
        val drugs = strengthItems(category)

        val tokens = NumberParser.tokenize(text)

        fun tokAt(k: Int): String = if (k in tokens.indices) tokens[k].trim('.') else ""

        /** An item noun at k, allowing modifiers in front of it. */
        fun itemFollows(from: Int): Boolean {
            var k = from
            while (k < tokens.size && tokAt(k) in modifiers && matchItem(tokAt(k), lexicon) == null) k++
            return matchItem(tokAt(k), lexicon) != null
        }

        fun numberRun(from: Int): Pair<Double?, Int> {
            var j = from
            while (j < tokens.size && NumberParser.isNumberToken(tokAt(j))) j++
            val value = if (j > from) NumberParser.parseNumberPhrase(tokens.subList(from, j)) else null
            return value to j
        }

        // In a LIST, a small bare number after an item is its count:
        // "maggi 4 packet, doodh 2 packet, bread 1".
        val isList = tokens.indices.count { matchItem(tokAt(it), lexicon) != null } >= 2

        val items = mutableListOf<LineItem>()
        var pendingQty: Double? = null
        var pendingUnit: String? = null
        // True when pendingQty arrived with a unit ("2kg"), making it a
        // measure a later bare number must not overwrite.
        var pendingMeasured = false
        var pendingModifier: String? = null

        var i = 0
        while (i < tokens.size) {
            val token = tokAt(i)

            if (token == NumberParser.BOUNDARY || token in CONJUNCTIONS) {
                pendingQty = null; pendingUnit = null; pendingModifier = null
                pendingMeasured = false
                i++
                continue
            }

            // Collect a number run.
            if (NumberParser.isNumberToken(token)) {
                val (value, j) = numberRun(i)
                val next = tokAt(j)

                // A number followed by a unit is a MEASURE.
                val measured = next in units

                // "paune sau ka chai" -- a price, not a count.
                if (value != null && next !in PRICE_MARKERS && next !in EXPLICIT_RATE_MARKERS) {
                    if (pendingQty != null && pendingMeasured && !measured) {
                        // A BARE number after an established measure is a
                        // price, not a replacement count: "2kg for" "500
                        // only" must not become five hundred kilos.
                    } else {
                        pendingQty = value
                        pendingMeasured = measured
                        if (!measured) pendingUnit = null
                    }
                }
                // A number between an adjective and a noun breaks them apart.
                pendingModifier = null
                i = j
                continue
            }

            // A container word is a unit only when it measures an item after
            // it: "1 box pen" is a box of pens, "1 geometry box" is a box.
            if (token in units &&
                !(token in CONTAINERS && matchItem(token, lexicon) != null && !itemFollows(i + 1))
            ) {
                pendingUnit = units[token]
                i++
                continue
            }

            // An adjective in front of a noun belongs to it. Words that are
            // nouns as well -- "chana", "amul", "dettol" -- only qualify the
            // noun that follows them; on their own they are the item.
            if (token in modifiers && (matchItem(token, lexicon) == null || itemFollows(i + 1))) {
                pendingModifier = token
                i++
                continue
            }

            // Two nouns that name one garment: "salwar kameez", "kurta pajama".
            val matched = matchItem(token, lexicon)
            val follower = matchItem(tokAt(i + 1), lexicon)
            if (matched != null && follower != null && CategoryLexicon.isCompound(matched, follower)) {
                pendingModifier = matched
                i++
                continue
            }

            if (matched != null) {
                val next = tokAt(i + 1)
                // A digit or a unit counts as corroboration; a weak number
                // word does not -- "daal do" is "put it in".
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
                var name = matched

                // A medicine's strength is part of its name: "dolo 650".
                if (matched in drugs && isNumeric(tokAt(i))) {
                    val (strength, j) = numberRun(i)
                    val after = tokAt(j)
                    if (strength != null && strength >= 50 && (
                            after in STRENGTH_UNITS || after in POSSESSIVES ||
                                NumberParser.isNumberToken(after) ||
                                after == NumberParser.BOUNDARY || after == ""
                            )
                    ) {
                        val s = if (strength == Math.floor(strength)) strength.toInt().toString()
                        else strength.toString()
                        name = "$matched $s"
                        i = if (after in STRENGTH_UNITS) j + 1 else j
                    }
                }

                // Trailing quantity: "chawal 2 kilo", with one possessive
                // allowed in between ("biscuit ke 4 packet"). Only with a unit
                // -- "chawal 300" is three hundred rupees of rice -- except a
                // small count inside a list.
                if (quantity == null && i < tokens.size) {
                    val k = if (tokAt(i) in POSSESSIVES && NumberParser.isNumberToken(tokAt(i + 1))) i + 1 else i
                    val lookahead = tokAt(k)
                    if (NumberParser.isNumberToken(lookahead) && lookahead != "do" && lookahead != "de") {
                        val (value, j) = numberRun(k)
                        if (value != null && tokAt(j) in units) {
                            quantity = value
                            unit = units[tokAt(j)]
                            i = j + 1
                        } else if (value != null && k == i && isList &&
                            value == Math.floor(value) && value > 0 && value <= 12 &&
                            (tokAt(j) == NumberParser.BOUNDARY || tokAt(j) == "" ||
                                tokAt(j) in CONJUNCTIONS || itemFollows(j))
                        ) {
                            quantity = value
                            i = j
                        }
                    }
                }

                // A unit AFTER the item, for a count stated before it.
                if (quantity != null && unit == null && tokAt(i) in units && tokAt(i) !in CONTAINERS) {
                    unit = units[tokAt(i)]
                    i++
                }

                items.add(
                    LineItem(
                        name = name,
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

        // One thing named, its measure said elsewhere in the message: "spring
        // onion chahiye, 2 kg", "chawal do kilo". Mirrors extract_items.
        if (items.size == 1 && items[0].quantity == null) {
            val found = mutableListOf<Pair<Double, String>>()
            var k = 0
            while (k < tokens.size) {
                if (NumberParser.isNumberToken(tokAt(k)) && !NumberParser.isVerbNotNumber(tokens, k)) {
                    val (value, j) = numberRun(k)
                    if (value != null && value > 0 && tokAt(j) in units && tokAt(j) !in CONTAINERS) {
                        found += value to units.getValue(tokAt(j))
                    }
                    k = maxOf(j, k + 1)
                    continue
                }
                k++
            }
            if (found.toSet().size == 1) {
                items[0] = items[0].copy(quantity = found[0].first, unit = found[0].second)
            } else if (found.isEmpty()) {
                // ...or a bare count right before an ordering word: "kurta 1
                // for 2000, 2 chahiye".
                val counts = mutableListOf<Double>()
                k = 0
                while (k < tokens.size) {
                    if (NumberParser.isNumberToken(tokAt(k)) && !NumberParser.isVerbNotNumber(tokens, k)) {
                        val (value, j) = numberRun(k)
                        if (value != null && value > 0 && value <= 100 && value == Math.floor(value) && tokAt(j) in COUNT_VERBS) {
                            counts += value
                        }
                        k = maxOf(j, k + 1)
                        continue
                    }
                    k++
                }
                if (counts.toSet().size == 1) items[0] = items[0].copy(quantity = counts[0])
            }
            // A rate stated before the thing, explicit markers only: "2000
            // each kurta" ("1 for 2000 kurta"), never "paune sau ka chai".
            if (items[0].unitPrice == null) {
                k = 0
                while (k < tokens.size && tokAt(k) != NumberParser.BOUNDARY) {
                    if (NumberParser.isNumberToken(tokAt(k)) && !NumberParser.isVerbNotNumber(tokens, k)) {
                        val (value, j) = numberRun(k)
                        if (value != null && value > 0 && tokAt(j) in EXPLICIT_RATE_MARKERS) {
                            items[0] = items[0].copy(unitPrice = value)
                            break
                        }
                        k = maxOf(j, k + 1)
                        continue
                    }
                    k++
                }
            }
        }

        return items
    }

    /** Words that say "I want this many": the number before them is a count. Mirrors `_COUNT_VERBS`. */
    private val COUNT_VERBS = setOf(
        "chahiye", "chaiye", "chahie", "chaahiye", "chaye", "chaiyay", "chahiyein",
        "dena", "dedo", "bhejna", "bhejo", "bhejdo", "lunga", "loonga", "lungi", "lenge",
        "चाहिए", "चाहिये", "भेजना", "लूंगा", "देना",
    )

    private val STRENGTH_UNITS = setOf("mg", "ml", "mcg")

    private fun isNumeric(token: String) = Regex("""\d+(?:\.\d+)?""").matches(token)

    /** Medicines, whose trailing number is a strength rather than a count. */
    private fun strengthItems(category: ShopCategory): Set<String> =
        if (category == ShopCategory.PHARMACY || category == ShopCategory.GENERAL) {
            CategoryLexicon.pharmacyItems()
        } else emptySet()

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
            val alone = isolated(norm, matched, numbers)
            val number = whole(matched)
            val patterns = listOf(
                Regex("${CURRENCY.pattern}\\s*$number", RegexOption.IGNORE_CASE),
                // A marker with a number right after it is THAT number's
                // prefix: in "bhej do ₹ 2000" the ₹ belongs to 2000.
                Regex("$number\\s*${CURRENCY.pattern}(?!\\s*\\d)", RegexOption.IGNORE_CASE),
            )
            if (patterns.any { it.containsMatchIn(alone) }) return value to "currency-adjacent"
        }

        // 2. Mode-adjacent -- "dhai sau ka udhaar".
        val modeWords = MODE_WORDS.keys.joinToString("|") { Regex.escape(it) }
        val lexicon = CategoryLexicon.itemsFor(category)
        for ((value, matched) in numbers) {
            val window = Regex("${whole(matched)}\\s+((?:\\w+\\s+){0,2})(?:$modeWords)")
            val hit = window.find(isolated(norm, matched, numbers))
            // "2 kurti udhaar 3000": the words between the number and the mode
            // word name the thing it counts, so it is a quantity, not the bill.
            // ...and when another number sits in between, that one is closer
            // to the mode word: "do kurti bhej do 2000 upi se" is Rs 2000.
            val between = hit?.groupValues?.get(1)?.split(Regex("""\s+"""))?.filter { it.isNotBlank() }.orEmpty()
            if (hit != null && between.none { matchItem(it, lexicon) != null } &&
                between.none { NumberParser.isNumberToken(it) }
            ) return value to "mode-adjacent"
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
            addAll(countValues(text, category))
        }
        // A rupee or two is not a bill: below ten, an unattached number is a
        // count the parser could not place. "2 packet parle g, 1 kurkure"
        // booked Rs 1. A real small price still arrives through the currency
        // and mode checks above.
        val spare = numbers.filter { it.value !in used && it.value >= 10 }
        if (spare.isNotEmpty()) {
            return spare.maxByOrNull { it.value }!!.value to "largest-unused"
        }

        // Every number was a quantity, so no price was stated. Falling
        // through to "largest overall" here would reuse a quantity AS the
        // amount: "2 kilo chawal bhej do" booked Rs 2. A missing amount is
        // recoverable -- the confirm sheet asks for it -- but a wrong one is
        // money out of someone's pocket.
        if (items.isNotEmpty()) return null to null

        // The same holds with no item recognised: a measure is never money.
        val candidates = numbers.filter { it.value !in used && it.value >= 10 }
        if (candidates.isEmpty()) return null to null
        return candidates.maxByOrNull { it.value }!!.value to "largest-overall"
    }

    /** Every number immediately followed by an item noun -- a count, never money. */
    /**
     * A number's words as a pattern that only matches the WHOLE number.
     * Unanchored, "2" matched the front of "₹ 2000" and "do" the front of
     * "do hazar udhaar", so both booked Rs 2. Mirrors `_whole` in rules.py.
     */
    private fun whole(matched: String): String {
        val tail = if (matched.lastOrNull()?.isDigit() == true) """(?!\d)(?!\.\d)""" else """(?![\p{L}])"""
        return """(?<![\w.])""" + Regex.escape(matched) + tail
    }

    /** The text with every LONGER number containing this one blanked out. Mirrors `_isolated`. */
    private fun isolated(norm: String, matched: String, numbers: List<NumberParser.Found>): String {
        val inner = Regex(whole(matched))
        var out = norm
        for (other in numbers) {
            if (other.matched.length > matched.length && inner.containsMatchIn(other.matched)) {
                out = Regex(whole(other.matched)).replace(out, "\u0001")
            }
        }
        return out
    }

    private fun countValues(text: String, category: ShopCategory): Set<Double> {
        val lexicon = CategoryLexicon.itemsFor(category)
        val modifiers = CategoryLexicon.modifiersFor(category)
        val tokens = NumberParser.tokenize(text)
        val found = mutableSetOf<Double>()
        var i = 0
        while (i < tokens.size) {
            if (!NumberParser.isNumberToken(tokens[i].trim('.'))) { i++; continue }
            var j = i
            while (j < tokens.size && NumberParser.isNumberToken(tokens[j].trim('.'))) j++
            var k = j
            while (k < tokens.size && tokens[k].trim('.') in modifiers) k++
            if (k < tokens.size && matchItem(tokens[k].trim('.'), lexicon) != null) {
                NumberParser.parseNumberPhrase(tokens.subList(i, j))?.let { found.add(it) }
            }
            i = maxOf(j, i + 1)
        }
        return found
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

        // One thing, priced for one, no count: "1 for 2000 kurta". The price
        // of the one is the bill. Mirrors apply_rules.
        val only = items.singleOrNull()
        if (amount == null && only != null && only.unitPrice != null && only.quantity == null) {
            return RuleResult(
                amount = only.unitPrice, amountSource = "rate-of-one", mode = mode, direction = direction,
                items = items, allNumbers = allNumbers, resolved = resolved,
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
