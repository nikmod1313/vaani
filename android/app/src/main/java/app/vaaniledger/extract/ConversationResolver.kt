package app.vaaniledger.extract

import app.vaaniledger.data.ShopCategory
import app.vaaniledger.extract.RulesEngine.LineItem
import app.vaaniledger.extract.RulesEngine.PaymentMode
import app.vaaniledger.net.Confidence
import app.vaaniledger.net.Extraction
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Conversation-level order resolution, on the phone.
 *
 * A port of `backend/app/extraction/conversation.py`, and the reason the app
 * no longer needs a server to read a chat. Customers do not place orders in
 * one message:
 *
 *     customer   i need aloo
 *     customer   2kg
 *     customer   what is the price?
 *     shopkeeper 500 for 1kg
 *     customer   i need 2 kilos
 *
 * Nothing in the last message names an item, nothing in the first names a
 * price, and the quantity is stated twice in different words. The order only
 * exists across the turns. This threads the two sides together and resolves
 * the reference: the item from whichever turn last named one, the quantity
 * from the turn that committed, the rate from what the shopkeeper quoted.
 *
 * THREE RULES DECIDE WHETHER AN ORDER EXISTS AT ALL, and they are the same
 * three the server applies -- OfflineParityTest holds the two to identical
 * answers, because an offline engine that books a different figure from the
 * dashboard is worse than no offline engine.
 *
 *   1. Only a customer can place an order. The shop naming a price is
 *      quoting, not buying.
 *   2. Asking is not buying. "Is aloo available?" sets the topic and books
 *      nothing. An order needs a commitment.
 *   3. A resolved order carries its evidence -- the real messages, shown
 *      to the shopkeeper -- separately from the tidied form the engine reads.
 */
object ConversationResolver {

    // ----------------------------------------------------------------------
    // Commitment
    // ----------------------------------------------------------------------
    //
    // Tuned for PRECISION. A false positive here does not add a card to a
    // queue -- it invents an order out of a question, attaches the last item
    // anyone mentioned, and asks the shopkeeper to confirm a sale that never
    // happened.

    private val COMMIT = listOf(
        // Hinglish -- wanting
        """\bchah?iye\b""", """\bchaeye\b""", """\bchaahiye\b""",
        """\bkharid(?:na|ne|ni|unga|enge)\b""",
        """\b(?:le|lena)\s+(?:lunga|loonga|lungi|hai|hain)\b""",
        """\b(?:lunga|loonga|lungi|lenge)\b""",
        """\bmangwa(?:na|do|dena)\b""",
        """\border\s+(?:karna|kar|karni|kardo|kar\s+do)\b""",
        // Hinglish -- instructing
        """\bbhej\s*(?:do|dena|dijiye|de)\b""", """\bbhejo\b""", """\bbhejna\b""",
        // A request phrased as a question is still a request: "bhej doge?"
        """\b(?:bhej|de|bhijwa|pack\s+kar)\s*(?:doge|denge|dogi|dijiyega|sakte|sakoge)\b""",
        """\bde\s*(?:do|dena|dijiye)\b""", """\bdedo\b""",
        """\bpack\s+kar\b""", """\bnikal\s*(?:do|dena)\b""",
        """\bbhijwa\s*(?:do|dena|dijiye|de)\b""", """\bbhejiye\b""", """\bbhej\s+dijie\b""",
        """\bdijiye\b""", """\brakh\s*(?:do|dena|dijiye|lena)\b""", """\bmangwa\w*\s+(?:do|dena|hai)\b""",
        """\b(?:le|lena)\s+(?:aana|ana|aao|jaunga|jayenge)\b""",
        // Writing it down IS the order for a regular customer.
        """\blikh\s*(?:lo|lena|do|dena|dijiye|lijiye)\b""", """\bjod\s*(?:do|dena|dijiye)\b""",
        """\badd\s+kar\w*\b""",
        // English
        """\bi\s+(?:want|need|will\s+take|'ll\s+take|ll\s+take)\b""",
        """\bwant\s+to\s+(?:buy|order|take)\b""",
        """\b(?:send|give|deliver)\s+me\b""",
        """\b(?:please|pls|plz|kindly)\s+(?:send|deliver|give|bhej\w*|de\s*do)\b""",
        """^\s*send\b""", """\bi\s+(?:would\s+like|require)\b""",
        """\bplace\s+an?\s+order\b""", """\bi\s+am\s+ordering\b""",
        """\bbook\s+(?:it|them|me)\b""",
        // Devanagari -- \b is unusable against matras, so whitespace bounds
        """(?<!\S)चाहिए""", """(?<!\S)खरीद\w*""", """(?<!\S)भेज\s*(?:दो|दीजिए|देना)""",
        """(?<!\S)दे\s*दो""", """(?<!\S)लूंगा""", """(?<!\S)लेना\s+है""",
        """(?<!\S)भेजना""", """(?<!\S)भेजिए""", """(?<!\S)भिजवा""", """(?<!\S)दीजिए""",
        """(?<!\S)लिख\s*(?:लो|लेना|दो|देना)""", """(?<!\S)रख\s*(?:दो|देना)""",
    ).map { Regex(it) }

    // Questions. These set the topic and must never book anything on their own.
    private val ENQUIRY = listOf(
        """\bavailab(?:le|ility)\b""", """\bstock\s+(?:hai|me|mein)\b""",
        """\bhai\s+kya\b""", """\bmilega\b""", """\bmilegi\b""", """\bhoga\b""",
        """\bkitne?\s+ka\b""", """\bkitna\b""", """\bprice\b""", """\brate\s+kya\b""",
        """\bhow\s+much\b""", """\bdo\s+you\s+have\b""", """\bis\s+there\b""",
        """(?<!\S)है\s*क्या""", """(?<!\S)कितने?\s+का""", """(?<!\S)मिलेगा""",
        """\bkya\s+(?:aapke|apke|tumhare)\s+paas\b""", """\bkaun\s*s[aei]\b""", """\bkaunse\b""",
        """\bkitne?\s+k[ie]\b""", """\bkya\s+rate\b""", """\bkab\s+tak\b""",
    ).map { Regex(it) }

    /** "Nahi chahiye" contains "chahiye". A cancellation is never the order it cancels. */
    private val NEGATION = listOf(
        """\bnah?i+n?\s+chah?iye\b""", """\bmat\s+(?:bhej|de|la|bana)\w*""", """\bcancel\w*\b""",
        """\bno\s+need\b""", """\bdon'?t\s+(?:send|need|want)\b""", """\bnot\s+(?:needed|required)\b""",
        """\bzarurat\s+nahi\b""", """(?<!\S)नहीं\s+चाहिए""", """(?<!\S)मत\s+भेज""",
    ).map { Regex(it) }

    /**
     * A message about goods that ALREADY moved -- a complaint, a return, a
     * chase. "kal 3 kg aata bheja tha wo kharab tha" names an item and a
     * quantity, and is the opposite of an order.
     */
    private val PAST = listOf(
        """\b(?:tha|thi|thaa)\b""", """\bkharaa?b\b""", """\bwapa?s\b""", """\breturn\w*\b""",
        """\bexchange\b""", """\bbadal\w*\b""", """\bnah?i\s+(?:aay?[ai]|mila|mili|pahunch\w*)\b""",
        """\bgalat\b""", """\bdamage\w*\b""", """\bexpire\w*\b""", """\bkam\s+(?:tha|thi|aaya|aayi|nikla)\b""",
        """\bmangwa(?:ya|yi|ye)\b""", """\bliya\s+tha\b""",
        """(?<!\S)थ[ाीे](?!\S)""", """(?<!\S)खराब""", """(?<!\S)वापस""",
    ).map { Regex(it) }

    /** What a shopkeeper says to announce a TOTAL: "250 hua", "4300 hoga", "done, 150". */
    private val TOTAL = listOf(
        """\btotal\b""", """\bhu[ae]\b""", """\bhui\b""", """\bho\s+gay[aei]\b""", """\bbill\b""", """\bkul\b""",
        """\bhog[ae]\b""", """\bbant[ae]\b""", """\bbanega\b""", """\blagenge?\b""", """\blagega\b""",
        """\bdone\b""", """\bsab\s+mila\s*ke\b""", """\bmilakar\b""",
        """(?<!\S)हुआ""", """(?<!\S)हुए""", """(?<!\S)कुल""", """(?<!\S)टोटल""", """(?<!\S)होगा""",
    ).map { Regex(it) }

    /** ...and a price for ONE: "450 ki ek", "10 ka ek", "500 each". */
    private val RATE = listOf(
        """\beach\b""", """\bper\b""", """\bprati\b""", """\b(?:ka|ki|ke)\s+(?:ek|1)\b""",
        """\b(?:ek|1)\s+(?:ka|ki|ke)\b""", """\bpiece\b""", """\bonwards\b""", """\bse\s+shuru\b""",
        """(?<!\S)प्रति""", """(?<!\S)(?:का|की)\s+एक""",
    ).map { Regex(it) }

    /** "write it down" on an amount with nothing named is still a khata entry. */
    private val CREDIT_ENTRY = Regex("""\blikh\w*|\budhaa?r\w*|\bkhaate?\b|(?<!\S)उधार|(?<!\S)लिख""")

    /**
     * A question about an arrangement, not an order: "kal se roz 2 packet
     * doodh chahiye hoga kya?". "bhej doge?" is still an order, so only the
     * asking words disqualify, not the question mark.
     */
    private val ASKING = Regex(
        """\b(?:hoga|hogi|honge|milega|milegi|milenge|mil\s+jaye?ga|mil\s+jayegi|available|ho\s+payega|""" +
            """chalega|kya)\b|(?<!\S)(?:होगा|मिलेगा|क्या)"""
    )

    /** Did this turn actually order something? */
    fun isCommitment(text: String): Boolean {
        val norm = NumberParser.normalise(text)
        if (NEGATION.any { it.containsMatchIn(norm) }) return false
        if ('?' in text && ASKING.containsMatchIn(norm)) return false
        return COMMIT.any { it.containsMatchIn(norm) }
    }

    /** Is this a question about availability or price? */
    fun isEnquiry(text: String): Boolean {
        if ('?' in text) return true
        val norm = NumberParser.normalise(text)
        return ENQUIRY.any { it.containsMatchIn(norm) }
    }

    /**
     * An order with no ordering verb: a list of things with quantities. Most
     * WhatsApp orders to a kirana arrive this way. Refused: a question, a
     * cancellation, a complaint about goods already delivered, a payment.
     */
    fun isImplicitOrder(text: String, rules: RulesEngine.RuleResult): Boolean {
        if (rules.items.none { it.quantity != null }) return false
        if (rules.direction == RulesEngine.Direction.PAYMENT || isEnquiry(text)) return false
        val norm = NumberParser.normalise(text)
        if (NEGATION.any { it.containsMatchIn(norm) }) return false
        return PAST.none { it.containsMatchIn(norm) }
    }

    // ----------------------------------------------------------------------
    // Turns and threads
    // ----------------------------------------------------------------------

    data class Turn(
        val sender: String,
        val body: String,
        val timestamp: LocalDateTime? = null,
        val isShopkeeper: Boolean = false,
        val contentHash: String = "",
    ) {
        val isCustomer: Boolean get() = !isShopkeeper
    }

    /**
     * One continuous exchange.
     *
     * A thread ends when the chat goes quiet for longer than the gap. Two
     * orders a week apart must not share a topic -- otherwise "3 chahiye" on
     * Friday attaches to the kurti someone asked about on Monday.
     */
    data class Thread(val customer: String, val turns: MutableList<Turn> = mutableListOf()) {
        val hashes: List<String> get() = turns.map { it.contentHash }.filter { it.isNotEmpty() }
    }

    /**
     * Split a chat into threads on conversational silence.
     *
     * Turns are sorted by timestamp first. Capture order is not chronological
     * order -- a scroll-harvest walks the screen upward. Kotlin's sortedBy is
     * stable, so turns with no timestamp keep the order they arrived in.
     */
    fun buildThreads(turns: List<Turn>, customer: String, gapMinutes: Long = 45): List<Thread> {
        val ordered = turns.sortedBy { it.timestamp ?: LocalDateTime.MIN }
        val threads = mutableListOf<Thread>()
        val gap = Duration.ofMinutes(gapMinutes)

        for (turn in ordered) {
            if (threads.isNotEmpty()) {
                val previous = threads.last().turns.last()
                val bothStamped = previous.timestamp != null && turn.timestamp != null
                val within = !bothStamped ||
                    Duration.between(previous.timestamp, turn.timestamp) <= gap
                if (within) {
                    threads.last().turns.add(turn)
                    continue
                }
            }
            threads.add(Thread(customer, mutableListOf(turn)))
        }
        return threads
    }

    // ----------------------------------------------------------------------
    // Resolution
    // ----------------------------------------------------------------------

    data class ResolvedOrder(
        val customer: String,
        val items: List<LineItem>,
        val amount: Double?,
        val mode: String,
        /** Canonical utterance, fed to the extraction engine. */
        val canonicalText: String,
        /** The real messages, shown to the shopkeeper. */
        val evidence: String,
        val occurredAt: LocalDateTime?,
        val contentHashes: List<String>,
        val signals: List<String>,
        /** The item came from an EARLIER turn than the commitment. */
        val carriedTopic: Boolean,
        /**
         * "sale" or "payment". A payment settles an existing balance and
         * carries no items; without this the lane could only ever book sales,
         * so a customer texting "i have completed the payment" left their
         * bill standing as outstanding for ever.
         */
        val direction: String = "sale",
    )

    /**
     * A quantity with no item attached: "mujhe 3 chahiye", "I want 3 pieces".
     *
     * The unit is optional -- "3 chahiye" is three of whatever is being
     * discussed, and refusing it because no unit was written would drop the
     * most common way a customer commits.
     */
    private fun bareQuantity(text: String, category: ShopCategory): Pair<Double?, String?> {
        val units = RulesEngine.unitTable(category)
        val tokens = NumberParser.normalise(text).split(" ").filter { it.isNotEmpty() }
        val numbers = NumberParser.findNumbers(text)
        if (numbers.isEmpty()) return null to null

        val (value, matched) = numbers.first()
        val tail = matched.split(" ").lastOrNull { it.isNotEmpty() } ?: matched

        var unit: String? = null
        for ((i, tok) in tokens.withIndex()) {
            if (tok.trim('.') == tail && i + 1 < tokens.size) {
                val candidate = tokens[i + 1].trim('.')
                if (candidate in units) unit = units[candidate]
                break
            }
        }
        return value to unit
    }

    /**
     * The quantity this message attaches to a unit: 2 in "2kg", 1 in "500 for 1kg".
     *
     * Looks for a number FOLLOWED BY A UNIT rather than any number, because a
     * bare number in a price quote is the price itself. The SIZE matters, not
     * merely the presence -- see the rate-versus-total logic in [resolve].
     */
    private fun measureQuantity(text: String, category: ShopCategory): Double? {
        val units = RulesEngine.unitTable(category)
        val tokens = NumberParser.tokenize(text)
        var i = 0
        while (i < tokens.size) {
            if (!NumberParser.isNumberToken(tokens[i].trim('.'))) { i++; continue }
            var j = i
            while (j < tokens.size && NumberParser.isNumberToken(tokens[j].trim('.'))) j++
            if (j < tokens.size && tokens[j].trim('.') in units) {
                NumberParser.parseNumberPhrase(tokens.subList(i, j))?.let { return it }
            }
            i = maxOf(j, i + 1)
        }
        return null
    }

    /** An item's identity across spellings: (modifier, noun), both canonical. */
    private fun key(item: LineItem): Pair<String, String> =
        CategoryLexicon.canonical(item.modifier) to CategoryLexicon.canonical(item.name)

    // The FIRST word for the thing is kept -- usually the customer's own.
    private fun merged(existing: LineItem, named: LineItem) = LineItem(
        name = existing.name,
        quantity = named.quantity ?: existing.quantity,
        unit = named.unit ?: existing.unit,
        modifier = named.modifier ?: existing.modifier,
        unitPrice = named.unitPrice ?: existing.unitPrice,
    )

    /**
     * Add a mention to a running set of items. The same name merges,
     * DIFFERENT names accumulate, and a mention without a modifier merges
     * into the single one that has one -- "2kg tamatar" then "organic grown
     * tamatar" is one line. Mirrors `_merge_item`.
     */
    private fun mergeItem(store: LinkedHashMap<Pair<String, String>, LineItem>, named: LineItem) {
        val key = key(named)
        val existing = store[key]
        if (existing != null) {
            store[key] = merged(existing, named)
            return
        }
        val same = store.keys.filter { it.second == key.second && (it.first.isEmpty() || key.first.isEmpty()) }
        if (same.size == 1) {
            val old = same[0]
            val prior = store.remove(old)!!
            store[if (key.first.isNotEmpty()) key else old] = merged(prior, named)
            return
        }
        store[key] = named
    }

    /** "400 kilo" -- a shopkeeper's price PER UNIT, said the short way. */
    private fun shopUnitRate(text: String, category: ShopCategory): Double? {
        val numbers = NumberParser.findNumbers(text)
        if (numbers.size != 1) return null
        val (value, matched) = numbers[0]
        val units = RulesEngine.unitTable(category)
        val tokens = NumberParser.normalise(text).split(" ").filter { it.isNotEmpty() }
        val tail = matched.split(" ").lastOrNull { it.isNotEmpty() } ?: matched
        for ((k, tok) in tokens.withIndex()) {
            if (tok.trim('.') == tail && k + 1 < tokens.size) {
                return if (tokens[k + 1].trim('.') in units && value >= 20) value else null
            }
        }
        return null
    }

    /**
     * Turn one thread into its orders: at most one sale and one payment.
     *
     * A port of `resolve_thread`. What a shopkeeper's number means depends
     * on what they SAY ("hua", "total", "each"), then on the size of any
     * quantity in the quote, then on WHEN it was said: before the customer
     * ordered it answers "what does it cost", after it "what do I owe".
     * Reading the second as a rate multiplied a real Rs 60 bill for two
     * strips of tablets to Rs 4,22,500.
     */
    /** A price for ONE unit: "1kg ka 200", "500 each", "200 per kg". Mirrors `_states_rate`. */
    private fun statesRate(text: String, rules: RulesEngine.RuleResult, category: ShopCategory): Boolean {
        val norm = NumberParser.normalise(text)
        if (TOTAL.any { it.containsMatchIn(norm) }) return false
        if (RATE.any { it.containsMatchIn(norm) } || rules.items.any { it.unitPrice != null }) return true
        return measureQuantity(text, category) == 1.0
    }

    fun resolveThread(thread: Thread, category: ShopCategory): List<ResolvedOrder> {
        if (thread.turns.isEmpty()) return emptyList()

        val topics = LinkedHashMap<Pair<String, String>, LineItem>()
        val ordered = LinkedHashMap<Pair<String, String>, LineItem>()
        var topicTurn: Turn? = null
        var paymentTurn: Turn? = null
        var paymentRules: RulesEngine.RuleResult? = null
        var paymentIndex = -1
        var quotedRate: Double? = null
        var quotedTotal: Double? = null
        var quotedTotalQty: Double? = null
        var shopTotal: Double? = null
        var shopTotalIndex = -1
        var customerAmount: Double? = null
        var shopRunQty: Double? = null
        var previousSpeakerWasShop: Boolean? = null
        var commitTurn: Turn? = null
        var commitRules: RulesEngine.RuleResult? = null
        var commitIndex = -1
        var lastItemCommitIndex = -1
        var creditEntry: Pair<Turn, Double>? = null
        val signals = mutableListOf<String>()

        for ((index, turn) in thread.turns.withIndex()) {
            val rules = RulesEngine.applyRules(turn.body, category)

            if (turn.isCustomer && rules.direction == RulesEngine.Direction.PAYMENT) {
                paymentTurn = turn; paymentRules = rules; paymentIndex = index
            }

            for (named in rules.items) {
                mergeItem(topics, named)
                topicTurn = turn
                named.unitPrice?.let { quotedRate = it }
            }

            if (turn.isShopkeeper) {
                if (previousSpeakerWasShop == false) shopRunQty = null
                val unitRate = shopUnitRate(turn.body, category)
                if (unitRate != null) {
                    quotedRate = unitRate
                    signals.add("shop-quoted-rate")
                } else {
                    var stated = measureQuantity(turn.body, category)
                    if (stated == null) stated = rules.items.lastOrNull()?.quantity
                    if (stated != null) shopRunQty = stated

                    val amount = rules.amount
                    if (amount != null) {
                        val norm = NumberParser.normalise(turn.body)
                        val saysTotal = TOTAL.any { it.containsMatchIn(norm) }
                        val saysRate = RATE.any { it.containsMatchIn(norm) } ||
                            rules.items.any { it.unitPrice != null }
                        val runQty = shopRunQty
                        when {
                            saysTotal && !saysRate -> {
                                shopTotal = amount; shopTotalIndex = index
                                signals.add("shop-stated-total")
                            }
                            saysRate -> { quotedRate = amount; signals.add("shop-quoted-rate") }
                            runQty != null && runQty > 1 -> {
                                quotedTotal = amount; quotedTotalQty = runQty
                                signals.add("shop-quoted-total")
                            }
                            runQty != null -> { quotedRate = amount; signals.add("shop-quoted-rate") }
                            commitIndex >= 0 || topics.size > 1 -> {
                                shopTotal = amount; shopTotalIndex = index
                                signals.add("shop-stated-total")
                            }
                            else -> { quotedRate = amount; signals.add("shop-quoted-rate") }
                        }
                    }
                }
            }
            previousSpeakerWasShop = turn.isShopkeeper

            // "1kg ka 200rs", "500 each", "1 for 1000" is a RATE whoever says
            // it -- in a group chat the person quoting may not be the phone's
            // owner. Mirrors conversation.py.
            val customerRate = turn.isCustomer && rules.amount != null &&
                rules.direction != RulesEngine.Direction.PAYMENT && statesRate(turn.body, rules, category)
            if (customerRate) {
                quotedRate = rules.amount
                signals.add("quoted-rate")
            }

            if (turn.isCustomer && (isCommitment(turn.body) || isImplicitOrder(turn.body, rules))) {
                commitTurn = turn; commitRules = rules; commitIndex = index
                if (rules.items.isNotEmpty()) {
                    rules.items.forEach { mergeItem(ordered, it) }
                    lastItemCommitIndex = index
                }
                if (rules.amount != null &&
                    rules.amountSource in setOf("currency-adjacent", "mode-adjacent", "line-totals") &&
                    rules.direction != RulesEngine.Direction.PAYMENT && !customerRate
                ) customerAmount = rules.amount
            }

            // The order told in pieces: "i want 2 kurtis", "blue color", "500
            // rs". A short follow-up that is only a colour or size describes
            // the one item already asked for; a price the customer adds after
            // ordering is that order's price. Mirrors conversation.py.
            if (turn.isCustomer && commitIndex >= 0 && index > commitIndex && rules.items.isEmpty()) {
                val words = NumberParser.normalise(turn.body).split(Regex("""\s+""")).filter { it.isNotEmpty() }
                val modifiers = CategoryLexicon.modifiersFor(category)
                val adjective = if (words.size <= 3) words.firstOrNull { it in modifiers } else null
                if (adjective != null) {
                    for (store in listOf(ordered, topics)) {
                        if (store.size == 1) {
                            val (onlyKey, only) = store.entries.first()
                            if (only.modifier == null) {
                                store.remove(onlyKey)
                                val described = only.copy(modifier = adjective)
                                store[key(described)] = described
                            }
                        }
                    }
                }
                if (customerAmount == null && rules.amount != null &&
                    rules.amountSource in setOf("currency-adjacent", "mode-adjacent") &&
                    rules.direction != RulesEngine.Direction.PAYMENT && !customerRate
                ) customerAmount = rules.amount
            }

            if (turn.isCustomer && rules.items.isEmpty() && rules.amount != null &&
                rules.amountSource in setOf("currency-adjacent", "mode-adjacent") &&
                rules.direction != RulesEngine.Direction.PAYMENT &&
                CREDIT_ENTRY.containsMatchIn(NumberParser.normalise(turn.body))
            ) creditEntry = turn to rules.amount
        }

        var sale = resolveSale(
            thread, category, topics, ordered, topicTurn, commitTurn, commitRules,
            quotedRate, quotedTotal, quotedTotalQty,
            if (shopTotalIndex > lastItemCommitIndex) shopTotal else null,
            customerAmount, signals, excludeModeFrom = paymentIndex,
        )

        val entry = creditEntry
        if (sale == null && entry != null) {
            val (turn, amount) = entry
            sale = ResolvedOrder(
                customer = thread.customer, items = emptyList(), amount = amount,
                mode = PaymentMode.UDHAAR.wire, canonicalText = turn.body,
                evidence = "${turn.sender}: ${turn.body}", occurredAt = turn.timestamp,
                contentHashes = thread.hashes, signals = listOf("credit-entry"),
                carriedTopic = false, direction = "sale",
            )
        }

        val paid = paymentTurn
        val paidRules = paymentRules
        var paidSeparately = paid != null && paidRules != null
        val current = sale
        if (current != null && paid != null && paidRules != null && paymentIndex > commitIndex && commitIndex >= 0) {
            // Money sent right after an order, for that order or with no
            // figure of its own, is how the order was PAID -- not a separate
            // debt being cleared.
            val paidAmount = paidRules.amount
            if (paidAmount == null || current.amount == null || Math.abs(paidAmount - current.amount) < 0.01) {
                val (detected, _) = RulesEngine.detectMode(paid.body)
                val mode = when {
                    detected != PaymentMode.UNKNOWN -> detected.wire
                    current.mode == PaymentMode.UNKNOWN.wire || current.mode == PaymentMode.UDHAAR.wire ->
                        PaymentMode.CASH.wire
                    else -> current.mode
                }
                sale = current.copy(
                    mode = mode,
                    amount = current.amount ?: paidAmount,
                    signals = current.signals + "paid-on-order",
                )
                paidSeparately = false
            }
        }

        val out = mutableListOf<ResolvedOrder>()
        sale?.let { out.add(it) }

        if (paidSeparately && paid != null && paidRules != null) {
            // A payment naming no instrument keeps "unknown": calling it cash
            // would be a guess the khata then reports as fact.
            val (detected, _) = RulesEngine.detectMode(paid.body)
            out.add(
                ResolvedOrder(
                    customer = thread.customer,
                    items = emptyList(),
                    amount = paidRules.amount,
                    mode = detected.wire,
                    canonicalText = paid.body,
                    evidence = "${paid.sender}: ${paid.body}",
                    occurredAt = paid.timestamp,
                    contentHashes = thread.hashes,
                    signals = (signals + "customer-paid").distinct(),
                    carriedTopic = false,
                    direction = "payment",
                )
            )
        }
        return out
    }

    private fun resolveSale(
        thread: Thread,
        category: ShopCategory,
        topics: LinkedHashMap<Pair<String, String>, LineItem>,
        ordered: LinkedHashMap<Pair<String, String>, LineItem>,
        topicTurn: Turn?,
        commit: Turn?,
        commitResult: RulesEngine.RuleResult?,
        quotedRate: Double?,
        quotedTotal: Double?,
        quotedTotalQty: Double?,
        shopTotal: Double?,
        customerAmount: Double?,
        signals: List<String>,
        excludeModeFrom: Int,
    ): ResolvedOrder? {
        if (commit == null || commitResult == null) return null
        // Committed to something we cannot name.
        if (topics.isEmpty()) return null

        val uniqueSignals = signals.distinct().toMutableList()
        uniqueSignals.add("customer-commitment")

        // ---- the items: everything committed to, else the carried topic --
        val items: MutableList<LineItem>
        val carried: Boolean
        if (ordered.isNotEmpty()) {
            items = ordered.values.toMutableList()
            carried = false
        } else {
            items = topics.values.map {
                LineItem(
                    name = it.name, quantity = null, unit = it.unit,
                    modifier = it.modifier, unitPrice = it.unitPrice,
                )
            }.toMutableList()
            carried = true
            uniqueSignals.add("carried-topic")
        }

        // ---- the quantity -------------------------------------------------
        if (items.size == 1 && items[0].quantity == null) {
            val (quantity, unit) = bareQuantity(commit.body, category)
            if (quantity != null) {
                items[0] = items[0].copy(quantity = quantity, unit = unit ?: items[0].unit)
                uniqueSignals.add("quantity-from-commitment")
            }
        }
        for (i in items.indices) {
            if (items[i].quantity != null) continue
            val established = topics[key(items[i])] ?: continue
            if (established.quantity != null) {
                items[i] = items[i].copy(
                    quantity = established.quantity,
                    unit = established.unit ?: items[i].unit,
                )
                if (!uniqueSignals.contains("quantity-from-topic")) uniqueSignals.add("quantity-from-topic")
            }
        }

        // ---- the rate: never on top of a total the shop already stated ----
        if (shopTotal == null) {
            for (i in items.indices) {
                if (items[i].unitPrice == null && quotedRate != null && items[i].quantity != null) {
                    items[i] = items[i].copy(unitPrice = quotedRate)
                    if (!uniqueSignals.contains("rate-from-quote")) uniqueSignals.add("rate-from-quote")
                }
            }
        }

        // ---- mode ---------------------------------------------------------
        var mode = PaymentMode.UNKNOWN.wire
        for ((index, turn) in thread.turns.withIndex()) {
            if (index == excludeModeFrom) continue
            val (detected, _) = RulesEngine.detectMode(turn.body)
            if (detected != PaymentMode.UNKNOWN) mode = detected.wire
        }

        // ---- canonical form -----------------------------------------------
        fun phrase(item: LineItem): String {
            val parts = mutableListOf<String>()
            item.quantity?.let { q ->
                parts.add(if (q == Math.floor(q)) q.toInt().toString() else q.toString())
                item.unit?.let { parts.add(it) }
            }
            item.modifier?.let { parts.add(it) }
            parts.add(item.name)
            item.unitPrice?.let { r ->
                parts.add(if (r == Math.floor(r)) r.toInt().toString() else r.toString())
                parts.add("each")
            }
            return parts.joinToString(" ")
        }

        var canonical = items.joinToString(" aur ") { phrase(it) }
        if (mode != PaymentMode.UNKNOWN.wire) canonical = "$canonical $mode"

        val evidence = thread.turns
            .filter { t ->
                t === topicTurn || t === commit ||
                    RulesEngine.applyRules(t.body, category).amount != null
            }
            .joinToString("\n") { "${it.sender}: ${it.body}" }

        // ---- the amount ---------------------------------------------------
        // The shop's stated total for THIS order, then rate x quantity, then
        // a quoted bundle total, then the customer's own figure. Nothing is
        // ever divided.
        val lineTotals = items.mapNotNull { it.lineTotal }
        var amount: Double? =
            if (items.isNotEmpty() && lineTotals.size == items.size) {
                Math.round(lineTotals.sum() * 100.0) / 100.0
            } else null

        if (shopTotal != null) {
            amount = shopTotal
        } else if (quotedTotal != null && amount == null) {
            amount = quotedTotal
            if (items.size == 1) {
                val q = items[0].quantity
                if (q != null && quotedTotalQty != null && Math.abs(q - quotedTotalQty) > 1e-9) {
                    uniqueSignals.add("bundle-quantity-mismatch")
                }
            }
        }
        if (amount == null && customerAmount != null) {
            amount = customerAmount
            uniqueSignals.add("customer-stated-amount")
        }
        if (amount == null && items.all { it.quantity == null }) amount = quotedRate

        return ResolvedOrder(
            customer = thread.customer,
            items = items,
            amount = amount,
            mode = mode,
            canonicalText = canonical,
            evidence = evidence.ifEmpty { "${commit.sender}: ${commit.body}" },
            occurredAt = commit.timestamp,
            contentHashes = thread.hashes,
            signals = uniqueSignals,
            carriedTopic = carried,
            direction = "sale",
        )
    }

    /** The thread's main order: its sale if it has one, else its payment. */
    fun resolve(thread: Thread, category: ShopCategory): ResolvedOrder? =
        resolveThread(thread, category).firstOrNull()

    /** Thread a whole chat and resolve every order in it. */
    fun resolveAll(
        turns: List<Turn>,
        customer: String,
        category: ShopCategory,
        gapMinutes: Long = 45,
        payLinkSent: List<LocalDateTime?> = emptyList(),
    ): List<ResolvedOrder> =
        linkLatePayments(buildThreads(turns, customer, gapMinutes).map { resolveThread(it, category) }, payLinkSent)

    /**
     * How long after an order a lone "payment done" is still read as paying
     * it. Threads split after 45 minutes of silence, which is right for an
     * ORDER and wrong for a PAYMENT, which typically arrives hours later,
     * after a reminder. Mirrors `LATE_PAYMENT_WINDOW`.
     */
    private val LATE_PAYMENT_WINDOW: Duration = Duration.ofDays(7)

    private val SENT = Regex("""\b(?:bhej\w*|sent|transfer\w*)\b|(?<!\S)भेज""")

    /**
     * How a later payment was made. Named in the message wins; money SENT
     * away from the counter went online, since cash cannot be sent over a
     * chat; anything else unnamed is cash, as on the same-thread path.
     */
    fun paidInstrument(text: String, mode: String): String = when {
        mode != PaymentMode.UNKNOWN.wire && mode != PaymentMode.UDHAAR.wire -> mode
        SENT.containsMatchIn(NumberParser.normalise(text)) -> PaymentMode.UPI.wire
        else -> PaymentMode.CASH.wire
    }

    /**
     * Fold a payment from a LATER thread into the credit order it pays.
     * Mirrors `link_late_payments` in conversation.py -- see there.
     *
     * The order is booked as udhaar, a reminder goes out with a UPI link,
     * and fifty minutes later "payment done" lands in a thread of its own.
     * It used to be booked as a separate payment while the order stayed
     * credit. A payment naming no figure, or the order's own figure, pays
     * THAT order. A part payment stays separate; inside one thread
     * [resolveThread] has already decided.
     */
    fun linkLatePayments(
        perThread: List<List<ResolvedOrder>>,
        payLinkSent: List<LocalDateTime?> = emptyList(),
    ): List<ResolvedOrder> {
        fun instrument(order: ResolvedOrder): String {
            if (order.mode != PaymentMode.UNKNOWN.wire) return order.mode
            val afterLink = payLinkSent.any { it == null || order.occurredAt == null || !it.isAfter(order.occurredAt) }
            return if (afterLink) PaymentMode.UPI.wire else PaymentMode.UNKNOWN.wire
        }

        val out = mutableListOf<ResolvedOrder>()
        for (orders in perThread) {
            val earlier = out.indices.toMutableList()
            for (raw in orders) {
                val order = if (raw.direction == "payment") raw.copy(mode = instrument(raw)) else raw
                val at = earlier.lastOrNull { out[it].direction == "sale" }
                val sale = at?.let { out[it] }
                if (
                    order.direction == "payment" && sale != null &&
                    (sale.mode == PaymentMode.UDHAAR.wire || sale.mode == PaymentMode.UNKNOWN.wire) &&
                    (order.amount == null || sale.amount == null || Math.abs(order.amount - sale.amount) < 0.01) &&
                    (order.occurredAt == null || sale.occurredAt == null ||
                        Duration.between(sale.occurredAt, order.occurredAt).let { !it.isNegative && it <= LATE_PAYMENT_WINDOW })
                ) {
                    val mode = paidInstrument(order.canonicalText, order.mode)
                    out[at] = sale.copy(
                        mode = mode,
                        amount = sale.amount ?: order.amount,
                        signals = sale.signals + "paid-later",
                        contentHashes = (sale.contentHashes + order.contentHashes).distinct(),
                        evidence = sale.evidence + "\n" + order.evidence,
                    )
                    earlier.remove(at)
                    continue
                }
                out += order
            }
        }
        return out
    }

    // ----------------------------------------------------------------------
    // Into the shape the review queue renders
    // ----------------------------------------------------------------------

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /**
     * A resolved order as an [Extraction], mirroring the server's
     * `_order_to_dict`.
     *
     * The engine is re-run over the CANONICAL text so confidence scoring and
     * name resolution stay in one place, but `text` is the EVIDENCE -- the
     * messages the customer actually sent. Showing someone a sentence they
     * never wrote, as proof of what they owe, loses the argument the khata
     * exists to settle.
     */
    fun ResolvedOrder.toExtraction(
        knownCustomers: List<String>,
        category: ShopCategory,
    ): Extraction {
        val base = OfflineExtractor.extract(
            text = canonicalText,
            knownCustomers = knownCustomers.ifEmpty { listOf(customer) },
            senderName = customer,
            category = category,
        )
        return base.copy(
            text = evidence,
            canonicalText = canonicalText,
            // The resolver did the arithmetic across turns; a single-message
            // re-parse cannot see a rate quoted three messages earlier.
            amount = amount ?: base.amount,
            mode = if (mode != PaymentMode.UNKNOWN.wire) mode else base.mode,
            direction = direction,
            lineItems = items.map { li ->
                app.vaaniledger.net.LineItem(
                    name = li.name,
                    modifier = li.modifier,
                    displayName = li.displayName,
                    quantity = li.quantity,
                    unit = li.unit,
                    unitPrice = li.unitPrice,
                    lineTotal = li.lineTotal,
                    label = li.label(),
                )
            },
            items = items.map { it.label() },
            occurredAt = occurredAt?.format(ISO),
            contentHashes = contentHashes,
            signals = signals,
            carriedTopic = carriedTopic,
            source = "conversation",
        )
    }
}
