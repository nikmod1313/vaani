package app.vaaniledger.data

import app.vaaniledger.extract.CategoryLexicon
import app.vaaniledger.net.Extraction

/**
 * Turning what was extracted into line-item rows, and recovering the ones
 * saved before the table existed.
 */
object LineItems {

    /**
     * The structured lines an extraction carries, as rows.
     *
     * Falls back to parsing the flat labels when `lineItems` is empty, which
     * happens for a manually corrected entry -- the edit sheet collects
     * labels, not structure.
     */
    fun from(extraction: Extraction, transaction: TransactionEntity): List<TransactionItemEntity> {
        val rows = extraction.lineItems.map { li ->
            TransactionItemEntity(
                transactionId = transaction.id,
                shopId = transaction.shopId,
                // The product key, not the raw word -- see CategoryLexicon.productKey.
                name = li.name.ifBlank { li.displayName }.ifBlank { li.label }
                    .let { CategoryLexicon.productKey(it, li.modifier) },
                displayName = li.displayName.ifBlank { li.name }.ifBlank { li.label },
                quantity = li.quantity,
                unit = li.unit,
                unitPrice = li.unitPrice,
                lineTotal = li.lineTotal,
                occurredAt = transaction.occurredAt,
            )
        }.filter { it.name.isNotBlank() }

        return rows.ifEmpty { fromLabels(transaction) }
    }

    /**
     * Recover structure from a stored label string.
     *
     * The labels were produced by `LineItem.label()` as
     * "<display name> <qty><unit>", so this is not open-ended text parsing --
     * it is reading back a format this codebase wrote. Anything that does not
     * match keeps its whole text as the name and reports no quantity, which
     * is honest: it still counts as a sale of that thing, just without a
     * measure.
     *
     * No price is recovered. A label never carried one, and dividing the
     * transaction total to invent a rate is the thing the engine refuses to
     * do everywhere else.
     */
    fun fromLabels(transaction: TransactionEntity): List<TransactionItemEntity> =
        transaction.itemList
            .filter { it.isNotBlank() }
            .map { label ->
                val match = LABEL.find(label.trim())
                val display = (match?.groupValues?.get(1) ?: label).trim()
                TransactionItemEntity(
                    transactionId = transaction.id,
                    shopId = transaction.shopId,
                    // The last word of a display name is the noun; anything
                    // before it is a modifier. Keyed as a product: "blue
                    // kurti" -> "kurti", "toor dal" -> "toor dal".
                    name = CategoryLexicon.productKeyFromDisplay(display),
                    displayName = display,
                    quantity = match?.groupValues?.get(2)?.toDoubleOrNull(),
                    unit = match?.groupValues?.get(3)?.ifBlank { null },
                    unitPrice = null,
                    lineTotal = null,
                    occurredAt = transaction.occurredAt,
                )
            }
            .filter { it.name.isNotBlank() }

    /** "<name> <qty><unit>" -- the exact shape label() emits. */
    private val LABEL = Regex("""^(.*?)\s+(\d+(?:\.\d+)?)\s*([A-Za-z]*)$""")
}
