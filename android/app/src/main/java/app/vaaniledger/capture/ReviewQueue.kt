package app.vaaniledger.capture

import android.content.Context
import app.vaaniledger.data.ChatCursorEntity
import app.vaaniledger.data.LedgerDatabase
import app.vaaniledger.data.LineItems
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.TransactionEntity
import app.vaaniledger.net.Extraction
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Orders waiting for the shopkeeper to approve them, and the one path that
 * turns an approval into money in the khata.
 *
 * WHY THE QUEUE IS IN MEMORY. Nothing here is persisted, and that is safe
 * for exactly one reason: the chat cursor is not advanced until an order is
 * approved. Kill the app mid-review and the same import offers the same
 * orders again. Persisting the queue as well would add a second copy of the
 * truth that could disagree with the cursor -- and the failure mode of that
 * disagreement is a double-booked sale.
 *
 * WHY THE WRITE LIVES HERE AND NOT IN EACH SCREEN. Live screening and export
 * import both end at [approve]. Two call sites writing transactions their own
 * way is how the phone and the dashboard start disagreeing about a number,
 * and the README is explicit that a shopkeeper who catches them disagreeing
 * stops trusting both.
 */
object ReviewQueue {

    /** One pending order plus the chat it came from. */
    data class Pending(
        val extraction: Extraction,
        val chatId: String,
        val customerName: String?,
    )

    @Volatile
    private var pending: List<Pending> = emptyList()

    /** Where the orders came from, shown at the top of the review screen. */
    @Volatile
    var sourceLabel: String = ""
        private set

    /**
     * The watermark key for a customer's chat.
     *
     * ONE id per customer, whichever lane the messages arrived by. The
     * screen lane and the export lane used to derive different ids for the
     * same conversation, so an order approved from the bubble was offered
     * again by the next export of that chat -- the two watermarks could not
     * see each other. The server docstring promised they shared a cursor;
     * the phone was the side that broke it.
     */
    fun chatIdFor(customer: String): String = "chat:" + customer.trim().lowercase()

    fun offer(orders: List<Pending>, label: String) {
        pending = orders
        sourceLabel = label
    }

    fun take(): List<Pending> = pending

    fun clear() {
        pending = emptyList()
        sourceLabel = ""
    }

    val size: Int get() = pending.size

    // ----------------------------------------------------------------------

    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    /**
     * Parse the server's occurred_at.
     *
     * Falls back to "now" rather than throwing. An order whose timestamp we
     * cannot read is still a real order; refusing to save it because a date
     * did not parse loses the entry, which is the one outcome worse than a
     * slightly wrong date.
     */
    private fun parseTimestamp(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching {
            ISO.apply { timeZone = TimeZone.getDefault() }
                .parse(value.substringBefore('.').removeSuffix("Z"))!!
                .time
        }.getOrDefault(System.currentTimeMillis())
    }

    /**
     * Approve one order: write the transaction, then record its messages.
     *
     * ORDER MATTERS. The transaction is saved BEFORE the cursor advances. Do
     * it the other way round and a crash between the two marks the messages
     * as processed while the sale was never written -- the order is gone, and
     * because the watermark now covers it, re-importing will not bring it
     * back. The reverse failure just re-offers an order the shopkeeper
     * already approved, and the transaction id makes the second save
     * idempotent.
     */
    suspend fun approve(context: Context, item: Pending): TransactionEntity {
        val db = LedgerDatabase.get(context)
        val extraction = item.extraction

        ShopSettings.persist(context)

        // The customer's own row, created on first sight. This is what gives
        // every customer a stable id across imports -- names arrive spelled
        // differently, but the khata must not fork because of it.
        val name = extraction.customer ?: item.customerName
        val customer = name
            ?.takeIf { it.isNotBlank() }
            ?.let { db.customers().resolve(ShopSettings.ID, it) }

        val transaction = TransactionEntity(
            shopId = ShopSettings.ID,
            customerId = customer?.id,
            customerName = customer?.name,
            amount = extraction.amount ?: 0.0,
            mode = extraction.mode.takeIf { it != "unknown" } ?: "udhaar",
            direction = extraction.direction.takeIf { it != "unknown" } ?: "sale",
            items = extraction.items.joinToString(","),
            source = "chat",
            // Human-approved. The engine's own score described a guess; once
            // someone has read it and tapped yes, it is not a guess.
            confidence = 1.0,
            occurredAt = parseTimestamp(extraction.occurredAt),
        )
        db.transactions().upsert(transaction)

        // The structured lines, kept rather than discarded. Without these the
        // analytics page cannot say which product sold, only what a bill
        // totalled. Cleared first so approving a corrected entry twice does
        // not double its items.
        db.analytics().clearItemsFor(transaction.id)
        db.analytics().insertItems(LineItems.from(extraction, transaction))

        recordSeen(context, item.chatId, extraction.contentHashes, transaction.occurredAt)
        return transaction
    }

    /**
     * Mark this order's messages as processed.
     *
     * Also used when the shopkeeper REJECTS an order -- a message declined
     * once must not come back on every future import, or the queue fills with
     * the same rejected card forever and stops being read.
     */
    suspend fun recordSeen(
        context: Context,
        chatId: String,
        hashes: List<String>,
        occurredAt: Long? = null,
    ) {
        if (hashes.isEmpty()) return
        val dao = LedgerDatabase.get(context).cursors()
        val existing = dao.byChat(chatId) ?: ChatCursorEntity(chatId = chatId)
        dao.upsert(
            existing.withSeen(hashes).copy(
                lastTimestamp = maxOf(existing.lastTimestamp ?: 0L, occurredAt ?: 0L)
                    .takeIf { it > 0L },
            )
        )
    }

    /** The stored watermark for a chat, in the shape the API expects. */
    suspend fun cursorFor(context: Context, chatId: String): app.vaaniledger.net.CursorPayload {
        val stored = LedgerDatabase.get(context).cursors().byChat(chatId)
        return app.vaaniledger.net.CursorPayload(
            chatId = chatId,
            lastTimestamp = null,
            seenHashes = stored?.hashes?.toList() ?: emptyList(),
        )
    }
}
