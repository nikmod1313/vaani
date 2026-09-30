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
        """\bde\s*(?:do|dena|dijiye)\b""", """\bdedo\b""",
        """\bpack\s+kar\b""", """\bnikal\s*(?:do|dena)\b""",
        // English
        """\bi\s+(?:want|need|will\s+take|'ll\s+take|ll\s+take)\b""",
        """\bwant\s+to\s+(?:buy|order|take)\b""",
        """\b(?:send|give|deliver)\s+me\b""",
        """\bplace\s+an?\s+order\b""", """\bi\s+am\s+ordering\b""",
        """\bbook\s+(?:it|them|me)\b""",
        // Devanagari -- \b is unusable against matras, so whitespace bounds
        """(?<!\S)चाहिए""", """(?<!\S)खरीद\w*""", """(?<!\S)भेज\s*(?:दो|दीजिए|देना)""",
        """(?<!\S)दे\s*दो""", """(?<!\S)लूंगा""", """(?<!\S)लेना\s+है""",
    ).map { Regex(it) }

    // Questions. These set the topic and must never book anything on their own.
    private val ENQUIRY = listOf(
        """\bavailab(?:le|ility)\b""", """\bstock\s+(?:hai|me|mein)\b""",
        """\bhai\s+kya\b""", """\bmilega\b""", """\bmilegi\b""", """\bhoga\b""",
        """\bkitne?\s+ka\b""", """\bkitna\b""", """\bprice\b""", """\brate\s+kya\b""",
        """\bhow\s+much\b""", """\bdo\s+you\s+have\b""", """\bis\s+there\b""",
        """(?<!\S)है\s*क्या""", """(?<!\S)कितने?\s+का""", """(?<!\S)मिलेगा""",
    ).map { Regex(it) }

    /** Did this turn actually order something? */
    fun isCommitment(text: String): Boolean {
        val norm = NumberParser.normalise(text)
        return COMMIT.any { it.containsMatchIn(norm) }
    }

    /** Is this a question about availability or price? */
    fun isEnquiry(text: String): Boolean {
        val norm = NumberParser.normalise(text)
        return ENQUIRY.any { it.containsMatchIn(norm) }
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

    /**
     * Turn one thread into at most one order.
     *
     * Returns null when the thread never committed to anything, which is the
     * common case and the point -- most chat is not an order.
     */
    fun resolve(thread: Thread, category: ShopCategory): ResolvedOrder? {
        if (thread.turns.isEmpty()) return null

        // Keyed by (modifier, name) and insertion-ordered, so "yellow kurti"
        // and "blue kurti" are two lines while two mentions of either are one.
        val topics = LinkedHashMap<Pair<String, String>, LineItem>()
        var topicTurn: Turn? = null
        var paymentTurn: Turn? = null
        var paymentRules: RulesEngine.RuleResult? = null
        var quotedRate: Double? = null
        var quotedTotal: Double? = null
        var quotedTotalQty: Double? = null
        var shopRunQty: Double? = null
        var previousSpeakerWasShop: Boolean? = null
        var commitTurn: Turn? = null
        var commitRules: RulesEngine.RuleResult? = null
        val signals = mutableListOf<String>()

        for (turn in thread.turns) {
            val rules = RulesEngine.applyRules(turn.body, category)

            // A customer saying the money has arrived settles a balance.
            // Checked before anything else, because a payment turn has no
            // items and the machinery below would throw the thread away.
            if (turn.isCustomer && rules.direction == RulesEngine.Direction.PAYMENT) {
                paymentTurn = turn
                paymentRules = rules
            }

            // Whoever names an item sets the topic. The same name merges,
            // keeping whichever mention stated each field; DIFFERENT names
            // accumulate. "8 yellow kurtis and 2 blue kurtis" is one order for
            // two things, and keeping only the last silently dropped four
            // thousand rupees of yellow kurtis off a real bill.
            for (named in rules.items) {
                val key = (named.modifier ?: "") to named.name
                val existing = topics[key]
                topics[key] = if (existing != null) {
                    LineItem(
                        name = named.name,
                        quantity = named.quantity ?: existing.quantity,
                        unit = named.unit ?: existing.unit,
                        modifier = named.modifier ?: existing.modifier,
                        unitPrice = named.unitPrice ?: existing.unitPrice,
                    )
                } else named
                topicTurn = turn
                named.unitPrice?.let { quotedRate = it }
            }

            // A price the shopkeeper states. RATE or TOTAL is decided by the
            // SIZE of the quantity in the quote:
            //
            //     "500 ka"        no quantity  -> what one costs     x qty
            //     "500 for 1kg"   quantity 1   -> a per-unit rate    x qty
            //     "2kg for 500"   quantity 2   -> a total for that bundle
            //
            // Runs rather than single messages, because a quote routinely
            // arrives split: "2kg for" then "500 only".
            if (turn.isShopkeeper) {
                if (previousSpeakerWasShop == false) shopRunQty = null
                var stated = measureQuantity(turn.body, category)
                if (stated == null) stated = rules.items.lastOrNull()?.quantity
                if (stated != null) shopRunQty = stated

                rules.amount?.let { amount ->
                    val runQty = shopRunQty
                    if (runQty != null && runQty > 1) {
                        quotedTotal = amount
                        quotedTotalQty = runQty
                        signals.add("shop-quoted-total")
                    } else {
                        quotedRate = amount
                        signals.add("shop-quoted-rate")
                    }
                }
            }
            previousSpeakerWasShop = turn.isShopkeeper

            if (turn.isCustomer && isCommitment(turn.body)) {
                commitTurn = turn
                commitRules = rules
            }
        }

        // ---- a payment settles a balance --------------------------------
        //
        // Emitted before the sale path and independently of it: a payment
        // needs no item, no commitment and no topic, so every guard below
        // would throw it away. The amount is whatever the message stated;
        // stating none returns null rather than a guess, and the review card
        // asks. Guessing would clear a debt by an amount nobody agreed.
        val paid = paymentTurn
        val paidRules = paymentRules
        if (paid != null && paidRules != null) {
            var paidMode = PaymentMode.UNKNOWN.wire
            for (turn in thread.turns) {
                val (detected, _) = RulesEngine.detectMode(turn.body)
                if (detected != PaymentMode.UNKNOWN) paidMode = detected.wire
            }
            return ResolvedOrder(
                customer = thread.customer,
                items = emptyList(),
                amount = paidRules.amount,
                mode = if (paidMode != PaymentMode.UNKNOWN.wire) paidMode else "cash",
                canonicalText = paid.body,
                evidence = "${paid.sender}: ${paid.body}",
                occurredAt = paid.timestamp,
                contentHashes = thread.hashes,
                signals = (signals + "customer-paid").distinct(),
                carriedTopic = false,
                direction = "payment",
            )
        }

        val commit = commitTurn ?: return null
        val commitResult = commitRules ?: return null
        // Committed to something we cannot name. An unnamed line in a khata
        // is not evidence of anything and cannot be checked later.
        if (topics.isEmpty()) return null

        val uniqueSignals = signals.distinct().toMutableList()
        uniqueSignals.add("customer-commitment")

        // ---- the items --------------------------------------------------
        //
        // ALL of them, not the last. The carried copies deliberately drop the
        // quantity: the commitment gets first claim on it below, and copying
        // it here let a number the topic never really stated win -- "do you
        // have red saree" parses the English "do" as the Hindi two, which
        // "i want 3 pieces" must be able to overrule.
        val items: MutableList<LineItem>
        val carried: Boolean
        if (commitResult.items.isNotEmpty()) {
            items = commitResult.items.toMutableList()
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

        // ---- the quantity -----------------------------------------------
        //
        // A bare number in the commitment can only attach when there is ONE
        // thing it could belong to. "2 chahiye" against a single topic is two
        // of that; against "yellow kurti and blue kurti" it is genuinely
        // ambiguous, and splitting or duplicating it would invent a quantity.
        if (items.size == 1 && items[0].quantity == null) {
            val (quantity, unit) = bareQuantity(commit.body, category)
            if (quantity != null) {
                items[0] = items[0].copy(quantity = quantity, unit = unit ?: items[0].unit)
                uniqueSignals.add("quantity-from-commitment")
            }
        }

        // Anything still without a quantity takes the one its own topic
        // established earlier in the thread.
        for (i in items.indices) {
            if (items[i].quantity != null) continue
            val established = topics[(items[i].modifier ?: "") to items[i].name] ?: continue
            if (established.quantity != null) {
                items[i] = items[i].copy(
                    quantity = established.quantity,
                    unit = established.unit ?: items[i].unit,
                )
                if (!uniqueSignals.contains("quantity-from-topic")) {
                    uniqueSignals.add("quantity-from-topic")
                }
            }
        }

        // ---- the rate ---------------------------------------------------
        // Only as a PER-UNIT price where the quantity is known; multiplying by
        // a guessed quantity is how a Rs 500 sale becomes Rs 1500.
        for (i in items.indices) {
            if (items[i].unitPrice == null && quotedRate != null && items[i].quantity != null) {
                items[i] = items[i].copy(unitPrice = quotedRate)
                if (!uniqueSignals.contains("rate-from-quote")) {
                    uniqueSignals.add("rate-from-quote")
                }
            }
        }

        // ---- mode -------------------------------------------------------
        var mode = PaymentMode.UNKNOWN.wire
        for (turn in thread.turns) {
            val (detected, _) = RulesEngine.detectMode(turn.body)
            if (detected != PaymentMode.UNKNOWN) mode = detected.wire
        }

        // ---- canonical form ---------------------------------------------
        // Rebuilt in the order the parser expects -- quantity, unit, modifier,
        // noun -- rather than by pasting turns together.
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

        // A quoted TOTAL is the amount outright. Ordering a DIFFERENT quantity
        // off a bundle quote is flagged, never scaled by division.
        val lineTotals = items.mapNotNull { it.lineTotal }
        var amount: Double? =
            if (items.isNotEmpty() && lineTotals.size == items.size) {
                Math.round(lineTotals.sum() * 100.0) / 100.0
            } else null

        val total = quotedTotal
        if (total != null && amount == null) {
            amount = total
            if (items.size == 1) {
                val q = items[0].quantity
                val bq = quotedTotalQty
                if (q != null && bq != null && Math.abs(q - bq) > 1e-9) {
                    uniqueSignals.add("bundle-quantity-mismatch")
                }
            }
        } else if (amount == null && items.all { it.quantity == null }) {
            amount = quotedRate
        }

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

    /** Thread a whole chat and resolve every order in it. */
    fun resolveAll(
        turns: List<Turn>,
        customer: String,
        category: ShopCategory,
        gapMinutes: Long = 45,
    ): List<ResolvedOrder> =
        buildThreads(turns, customer, gapMinutes).mapNotNull { resolve(it, category) }

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
