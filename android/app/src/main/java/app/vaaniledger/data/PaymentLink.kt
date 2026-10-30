package app.vaaniledger.data

/**
 * A payment that pays an order already in the khata on credit.
 *
 * The order was discussed, booked as udhaar and a reminder sent; an hour
 * later the customer writes "payment done" or "UPI kar diya". That message
 * arrives alone -- the order's own messages were marked seen when it was
 * approved -- and used to be booked as a second, separate payment row while
 * the order itself still read "udhaar". When the payment is for exactly that
 * order, the order is updated instead: same row, same id, its mode switched
 * to how it was paid. The server keys on the id, so the next sync updates the
 * dashboard's copy rather than adding one. Applied in [app.vaaniledger.capture.ReviewQueue.payOrderOnCredit].
 *
 * Only a payment that settles whole orders is folded. A part payment stays a
 * payment row, and oldest-first aging settles the part, because the order
 * really was on credit.
 */
object PaymentLink {

    /**
     * Which of one customer's credit sales [amount] pays outright.
     *
     * @param chronological the customer's live rows, oldest first.
     * @param amount what was paid; null when the message named no figure,
     *   which pays the latest order still fully on credit.
     *
     * Prefers the LATEST untouched credit sale whose amount matches -- the
     * order the reminder was about -- then every open credit together when
     * the payment equals their sum. Anything else matches nothing.
     */
    fun creditsPaidBy(chronological: List<TransactionEntity>, amount: Double?): List<TransactionEntity> {
        val open = openCredits(chronological)
        val untouched = open.filter { (tx, remaining) -> Math.abs(tx.amount - remaining) < 0.01 }.map { it.first }
        if (untouched.isEmpty()) return emptyList()
        if (amount == null) return listOf(untouched.last())
        untouched.lastOrNull { Math.abs(it.amount - amount) < 0.01 }?.let { return listOf(it) }
        val all = open.sumOf { it.second }
        if (untouched.size == open.size && Math.abs(all - amount) < 0.01) return untouched
        return emptyList()
    }

    /** Credit sales still owed, with what remains on each, after oldest-first settling. Mirrors [Aging.settle]. */
    private fun openCredits(chronological: List<TransactionEntity>): List<Pair<TransactionEntity, Double>> {
        val debts = ArrayDeque<Pair<TransactionEntity, Double>>()
        var advance = 0.0
        for (tx in chronological) {
            if (tx.deleted) continue
            when {
                tx.direction == Direction.SALE.wire && tx.mode == PaymentMode.UDHAAR.wire -> {
                    val used = minOf(tx.amount, advance)
                    advance -= used
                    if (tx.amount - used > 0) debts.addLast(tx to (tx.amount - used))
                }
                tx.direction == Direction.PAYMENT.wire -> {
                    var credit = tx.amount
                    while (credit > 0 && debts.isNotEmpty()) {
                        val (sale, remaining) = debts.removeFirst()
                        if (remaining <= credit) credit -= remaining
                        else { debts.addFirst(sale to (remaining - credit)); credit = 0.0 }
                    }
                    advance += credit
                }
            }
        }
        return debts.toList()
    }
}
