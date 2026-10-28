package app.vaaniledger.capture

import android.content.Context
import app.vaaniledger.data.ChatCursorEntity
import app.vaaniledger.data.LedgerDatabase
import app.vaaniledger.data.LineItems
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.TransactionEntity
import app.vaaniledger.net.Extraction
import java.security.MessageDigest
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
     * The content-derived identity for an entry.
     *
     * Built from the SOURCE MESSAGE HASHES when the extraction has them, so
     * the same conversation yields the same key no matter which lane read it
     * or how many times. Sorted first, because the screen lane and the export
     * lane can legitimately encounter the same messages in different orders.
     *
     * Falls back to the entry's own fields for manual and voice entries,
     * which have no source messages. Two of those colliding would need the
     * same customer, amount, direction and items in the same millisecond.
     */
    fun dedupeKey(extraction: Extraction, shopId: String, occurredAt: Long): String {
        val basis = if (extraction.contentHashes.isNotEmpty()) {
            shopId + "|" + extraction.contentHashes.sorted().joinToString(",")
        } else {
            listOf(
                shopId,
                extraction.customer.orEmpty().trim().lowercase(),
                occurredAt.toString(),
                extraction.amount?.toString().orEmpty(),
                extraction.direction,
                extraction.items.sorted().joinToString(","),
            ).joinToString("|")
        }
        return MessageDigest.getInstance("SHA-1")
            .digest(basis.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Parse the server's occurred_at.
     *
     * Falls back to "now" rather than throwing. An order whose timestamp we
     * cannot read is still a real order; refusing to save it because a date
     * did not parse loses the entry, which is the one outcome worse than a
     * slightly wrong date.
     */
    internal fun parseTimestamp(value: String?): Long {
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

        val occurredAt = parseTimestamp(extraction.occurredAt)
        val key = dedupeKey(extraction, ShopSettings.ID, occurredAt)

        // Reuse the row this content already made, if it made one. Keeping
        // its id is what makes the server sync idempotent as well -- the
        // server keys on the client's id, so a fresh UUID here would land as
        // a second sale there however careful the phone was.
        val existing = db.transactions().byDedupeKey(key)

        val transaction = TransactionEntity(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
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
            occurredAt = occurredAt,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            dedupeKey = key,
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
     * Is this order already in the khata?
     *
     * Same customer, same amount, same direction, within half a day of when
     * it happened. Capturing the same chat twice must never book the same
     * money twice -- a missed repeat order costs one manual entry, a double
     * booking costs the shop's word with its customer.
     */
    suspend fun alreadyBooked(context: Context, extraction: Extraction, customerName: String?): Boolean {
        val name = (extraction.customer ?: customerName)?.takeIf { it.isNotBlank() } ?: return false
        val amount = extraction.amount?.takeIf { it > 0 } ?: return false
        val db = LedgerDatabase.get(context)
        // The stored name is the customer row's spelling, not the chat's.
        val stored = db.customers().byName(ShopSettings.ID, name)?.name ?: name
        val at = parseTimestamp(extraction.occurredAt)
        val direction = extraction.direction.takeIf { it != "unknown" } ?: "sale"
        return db.transactions().lookalike(
            ShopSettings.ID, stored, amount, direction, at - HALF_DAY, at + HALF_DAY,
        ) != null
    }

    private const val HALF_DAY = 12 * 60 * 60 * 1000L

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
