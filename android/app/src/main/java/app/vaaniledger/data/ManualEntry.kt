package app.vaaniledger.data

import android.content.Context
import app.vaaniledger.overlay.EditSheetView

/**
 * An entry the shopkeeper writes by hand.
 *
 * Until now every line in the khata had to arrive through a chat, an export
 * or a voice note. But half of a counter's day is a regular saying "likh
 * lena" in person, or paying off last week's balance in cash -- and with no
 * way to write that down, the khata disagreed with the drawer by the end of
 * the day. This is the paper bahi's pen.
 *
 * Saved exactly like an approved capture -- the customer resolved to one row,
 * line items derived for the report, PENDING for the next sync -- with
 * `source = "manual"` so the audit trail says who wrote it.
 */
object ManualEntry {

    suspend fun save(context: Context, entry: EditSheetView.Corrected): TransactionEntity {
        val db = LedgerDatabase.get(context)
        ShopSettings.persist(context)

        val customer = entry.customer?.takeIf { it.isNotBlank() }
            ?.let { db.customers().resolve(ShopSettings.ID, it) }

        val payment = entry.direction == "payment"
        val transaction = TransactionEntity(
            shopId = ShopSettings.ID,
            customerId = customer?.id,
            customerName = customer?.name,
            amount = entry.amount ?: 0.0,
            // Money coming back is never "udhaar": it settles udhaar.
            mode = if (payment && entry.mode == "udhaar") "cash" else entry.mode,
            direction = entry.direction,
            // A payment carries no goods.
            items = if (payment) "" else entry.items.joinToString(","),
            source = "manual",
            confidence = 1.0,
            syncState = SyncState.PENDING.name,
        )
        db.transactions().upsert(transaction)
        if (!payment) db.analytics().insertItems(LineItems.fromLabels(transaction))
        return transaction
    }
}
