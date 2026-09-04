package app.vaaniledger.extract

import app.vaaniledger.data.ShopCategory
import app.vaaniledger.net.Confidence
import app.vaaniledger.net.Extraction
import app.vaaniledger.net.FieldScore
import app.vaaniledger.net.LineItem

/**
 * The on-device extraction pipeline.
 *
 * Ports the single-message path of `backend/app/extraction/pipeline.py` plus
 * `confidence.py`, and returns the SAME [Extraction] type the server does --
 * so the confirm sheet, the review queue and everything downstream cannot
 * tell where a result came from and needed no changes at all.
 *
 * WHAT THIS DOES NOT DO, deliberately:
 *
 * **No LLM stage.** There is no network, so there is no model. The Python
 * pipeline already degrades to rules-only when a model call fails; offline is
 * the same degrade taken one step further, and the confidence score reflects
 * it honestly rather than pretending.
 *
 * **No cross-turn conversation threading.** Resolving an order stated across
 * several messages ("blue kurti?" ... "500 ka" ... "mujhe 3 chahiye") stays
 * server-side. Offline handles the single utterance, which is the common
 * counter case; a multi-turn negotiation still gets the full engine as soon
 * as there is a connection.
 *
 * **No fuzzy name matching.** `names.py` does transliteration-aware fuzzy
 * customer matching; this does case-insensitive exact matching against the
 * known-customer list. A near-miss here creates a new customer rather than
 * silently merging two -- the safer failure, since a duplicate khata can be
 * merged later but money posted to the wrong person's account cannot be
 * un-seen.
 */
object OfflineExtractor {

    private const val AUTO_THRESHOLD = 0.85
    private const val ASK_THRESHOLD = 0.50

    // Amount dominates: getting it wrong is the only error that directly
    // misstates money.
    private val FIELD_WEIGHTS = mapOf(
        "amount" to 0.40,
        "customer" to 0.25,
        "mode" to 0.20,
        "items" to 0.15,
    )

    /**
     * Run one message through the on-device pipeline.
     *
     * @param senderName the chat contact the message came from -- the
     *   strongest customer signal available, and free.
     */
    fun extract(
        text: String,
        knownCustomers: List<String> = emptyList(),
        senderName: String? = null,
        category: ShopCategory = ShopCategory.GENERAL,
    ): Extraction {
        val rules = RulesEngine.applyRules(text, category)

        // ---- customer ----------------------------------------------------
        val matched = senderName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { name ->
                knownCustomers.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
            }
        val isNew = matched != null &&
            knownCustomers.none { it.equals(matched, ignoreCase = true) }

        // ---- confidence, per field ---------------------------------------
        val (_, modeWord) = RulesEngine.detectMode(text)
        val scores = linkedMapOf(
            "amount" to scoreAmount(rules.amount, rules.amountSource),
            "customer" to scoreCustomer(matched, isNew),
            "mode" to scoreMode(rules.mode.wire, modeWord),
            "items" to scoreItems(rules.items, statedRate(text), rules.direction.wire),
        )

        val overall = weightedOverall(scores)
        val askAbout = scores.entries
            .filter { it.value.score < AUTO_THRESHOLD }
            .sortedBy { it.value.score }
            .map { it.key }

        return Extraction(
            text = text,
            customer = matched,
            customerIsNew = isNew,
            amount = rules.amount,
            mode = rules.mode.wire,
            direction = rules.direction.wire,
            items = rules.items.map { it.label() },
            lineItems = rules.items.map { it.toDto() },
            confidence = Confidence(
                overall = overall,
                route = when {
                    overall >= AUTO_THRESHOLD -> "auto"
                    overall >= ASK_THRESHOLD -> "ask"
                    else -> "manual"
                },
                fields = scores,
                askAbout = askAbout,
            ),
            // Names where this came from, so a shopkeeper reviewing an entry
            // later can tell it was parsed without the full engine.
            source = "rules-offline",
        )
    }

    private fun RulesEngine.LineItem.toDto() = LineItem(
        name = name,
        modifier = modifier,
        displayName = displayName,
        quantity = quantity,
        unit = unit,
        unitPrice = unitPrice,
        lineTotal = lineTotal,
        label = label(),
    )

    // ----------------------------------------------------------------------
    // Scoring -- ports of confidence.py
    // ----------------------------------------------------------------------

    private fun round3(value: Double) = Math.round(value * 1000.0) / 1000.0

    private fun weightedOverall(scores: Map<String, FieldScore>): Double {
        var total = 0.0
        var totalWeight = 0.0
        for ((name, weight) in FIELD_WEIGHTS) {
            val score = scores[name] ?: continue
            total += score.score * weight
            totalWeight += weight
        }
        return if (totalWeight > 0) round3(total / totalWeight) else 0.0
    }

    private fun scoreAmount(amount: Double?, source: String?): FieldScore {
        if (amount == null) return FieldScore(0.0, "no number found")
        val base = when (source) {
            "currency-adjacent" -> 0.96
            // A stated rate times a stated quantity. Both halves came out of
            // the message, so the total is arithmetic rather than a pick --
            // which makes it the one source MORE trustworthy than a currency
            // marker, since nothing had to be chosen between competing
            // numbers.
            "line-totals" -> 0.97
            "mode-adjacent" -> 0.88
            "largest-unused" -> 0.55
            "largest-overall" -> 0.40
            else -> 0.40
        }
        return FieldScore(round3(base), "picked by $source")
    }

    private fun scoreCustomer(name: String?, isNew: Boolean): FieldScore = when {
        name.isNullOrBlank() -> FieldScore(0.0, "no name found")
        // A new customer is not an error, but it deserves a look.
        isNew -> FieldScore(0.60, "new customer, no khata yet")
        else -> FieldScore(0.99, "matched existing khata")
    }

    private fun scoreMode(mode: String, matchedWord: String?): FieldScore =
        if (mode == "unknown") FieldScore(0.25, "no payment word found")
        else FieldScore(0.95, matchedWord?.let { "keyword '$it'" } ?: "phrase match")

    private fun scoreItems(
        items: List<RulesEngine.LineItem>,
        ratesKnown: Boolean,
        direction: String,
    ): FieldScore {
        // A payment settles a balance; it has no line items. Penalising their
        // absence would route every payment to "ask" about a field that
        // cannot exist.
        if (direction == "payment") return FieldScore(1.0, "not applicable to a payment")
        if (items.isEmpty()) return FieldScore(0.30, "no items recognised")

        val missingQty = items.count { it.quantity == null }
        // A per-item rate parsed off the message is direct evidence and beats
        // the caller's textual guess.
        val priced = items.count { it.unitPrice != null }
        val haveRates = ratesKnown || priced == items.size

        var base = 0.90
        val reasons = mutableListOf("${items.size} item(s)")
        if (missingQty > 0) {
            base -= 0.20 * (missingQty.toDouble() / items.size)
            reasons.add("$missingQty without quantity")
        }
        if (priced > 0) reasons.add("$priced with a stated rate")
        if (!haveRates) {
            base -= 0.25
            reasons.add("rate not stated")
        }
        return FieldScore(round3(maxOf(base, 0.1)), reasons.joinToString("; "))
    }

    /** Did the message state a per-unit rate, or only a total? */
    private fun statedRate(text: String): Boolean {
        val lowered = text.lowercase()
        return listOf("/kg", "per kg", "rate", "ke hisab", "/-", " each", " per ", "prati")
            .any { it in lowered }
    }
}
