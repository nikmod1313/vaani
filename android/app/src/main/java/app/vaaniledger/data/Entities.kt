package app.vaaniledger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Room entities. The schema mirrors `docs/api.yaml` and the server's SQLite
 * store field for field, so sync is a copy rather than a translation.
 *
 * The one rule that matters most: transaction ids are generated HERE, on the
 * phone, not by the server. The phone is where the shopkeeper confirmed the
 * entry, so the phone owns its identity -- and that is what makes a retried
 * sync idempotent instead of a second charge.
 */

enum class SyncState { PENDING, SYNCED, FAILED }

enum class PaymentMode(val wire: String) {
    CASH("cash"), UPI("upi"), UDHAAR("udhaar");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.wire == value } ?: CASH
    }
}

enum class Direction(val wire: String) {
    SALE("sale"), PAYMENT("payment");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.wire == value } ?: SALE
    }
}

/** Where an entry came from. Shown on every row -- this is the audit trail. */
enum class Source(val wire: String) {
    CHAT("chat"), VOICE("voice"), OCR("ocr"), MANUAL("manual"), EXPORT("export");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.wire == value } ?: MANUAL
    }
}


@Entity(tableName = "shops")
data class ShopEntity(
    @PrimaryKey val id: String,
    val name: String,
    val language: String = "hi-IN",
    val speaker: String = "anushka",
    /**
     * What the shop sells. Chooses the item lexicon the extraction engine
     * uses, because one closed grocery word list cannot name a single thing
     * a clothes shop stocks.
     *
     * Defaults to "general", which on the server means the OPEN vocabulary --
     * every word every category knows -- not an empty one. So a shop that
     * never picks still recognises items; picking just makes it sharper.
     */
    val category: String = ShopCategory.GENERAL.wire,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)


/**
 * Shop categories, mirroring `backend/app/extraction/categories.py`.
 *
 * The labels live here so the picker renders instantly on first launch,
 * before the phone has ever reached the server. `GET /categories` is the
 * authority and the list refreshes from it, but a shopkeeper setting the app
 * up on a counter with no signal must still be able to choose.
 */
enum class ShopCategory(val wire: String, val label: String) {
    KIRANA("kirana", "Kirana / General Store"),
    APPAREL("apparel", "Kapde / Clothing"),
    PHARMACY("pharmacy", "Dawai / Medical"),
    HARDWARE("hardware", "Hardware / Building"),
    STATIONERY("stationery", "Stationery / Books"),
    ELECTRONICS("electronics", "Electronics / Mobile"),
    GENERAL("general", "Doosra / Other");

    companion object {
        fun from(value: String?) =
            entries.firstOrNull { it.wire == value } ?: GENERAL
    }
}


@Entity(
    tableName = "customers",
    indices = [Index(value = ["shop_id", "name"], unique = true)],
)
data class CustomerEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "shop_id") val shopId: String,
    val name: String,
    val phone: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)


@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["shop_id", "occurred_at"]),
        Index(value = ["customer_id"]),
        Index(value = ["sync_state"]),
    ],
)
data class TransactionEntity(
    /** Client-generated. Never let the server assign this. */
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "shop_id") val shopId: String,
    @ColumnInfo(name = "customer_id") val customerId: String? = null,
    @ColumnInfo(name = "customer_name") val customerName: String? = null,
    val amount: Double,
    val mode: String,
    val direction: String = "sale",
    /** Comma-separated labels, matching the server's storage. */
    val items: String = "",
    val source: String = "manual",
    val confidence: Double = 0.0,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    @ColumnInfo(name = "sync_state") val syncState: String = SyncState.PENDING.name,
) {
    val itemList: List<String>
        get() = if (items.isBlank()) emptyList() else items.split(",").map { it.trim() }

    /** Positive increases what the customer owes; a payment reduces it. */
    val signedAmount: Double
        get() = if (direction == Direction.PAYMENT.wire) -amount else amount
}


/**
 * Per-chat high-water mark for bulk ingestion.
 *
 * Deciding whether a message was already processed is a hash comparison, not
 * a language problem. A model that is 99% right about "have I seen this"
 * still double-charges someone one time in a hundred.
 */
@Entity(tableName = "chat_cursors")
data class ChatCursorEntity(
    @PrimaryKey @ColumnInfo(name = "chat_id") val chatId: String,
    @ColumnInfo(name = "last_timestamp") val lastTimestamp: Long? = null,
    /** Rolling window of recent content hashes, newline-separated. */
    @ColumnInfo(name = "seen_hashes") val seenHashes: String = "",
) {
    val hashes: Set<String>
        get() = if (seenHashes.isBlank()) emptySet() else seenHashes.lines().toSet()

    fun withSeen(newHashes: Collection<String>, limit: Int = 500): ChatCursorEntity {
        val merged = (hashes + newHashes).toList().takeLast(limit)
        return copy(seenHashes = merged.joinToString("\n"))
    }
}


/**
 * Messages captured by the notification listener, waiting to be processed.
 *
 * Lane 3 of ingestion: staged the moment they arrive, so the backlog is ready
 * before the shopkeeper asks for it.
 */
@Entity(tableName = "staged_messages", indices = [Index(value = ["content_hash"], unique = true)])
data class StagedMessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "chat_id") val chatId: String,
    val sender: String,
    val body: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long = System.currentTimeMillis(),
    val processed: Boolean = false,
)


/**
 * A Badlo. Every correction the shopkeeper makes is kept and later replayed
 * to the extraction engine as a few-shot example for this shop.
 */
@Entity(tableName = "corrections")
data class CorrectionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "shop_id") val shopId: String,
    @ColumnInfo(name = "raw_text") val rawText: String,
    /** JSON blobs, sent to /corrections verbatim. */
    val predicted: String,
    val corrected: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
)


/**
 * One line of an order, kept as its own row.
 *
 * Transactions store their items as a flattened label string
 * ("chawal 2kg,chai 1packet") which is fine for showing a row back to the
 * shopkeeper and useless for analytics. Two things are unrecoverable from
 * it: which NAME was sold, once a modifier is folded in ("blue kurti 3"),
 * and how much of a two-item bill belonged to each line. "Highest selling
 * product" computed off that string would be a guess dressed as a number.
 *
 * The extraction engine already produces all of this -- name, quantity,
 * unit, stated rate, line total -- and it was simply being discarded at save
 * time. Now it is kept.
 *
 * `shopId` and `occurredAt` are DENORMALISED from the parent transaction so
 * every analytics query can group by month or product without joining. They
 * are written once, at save, and never updated -- an item cannot move to
 * another shop or another day without the transaction itself being rewritten.
 */
@Entity(
    tableName = "transaction_items",
    indices = [
        Index(value = ["transaction_id"]),
        Index(value = ["shop_id", "occurred_at"]),
        Index(value = ["shop_id", "name"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TransactionItemEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "transaction_id") val transactionId: String,
    @ColumnInfo(name = "shop_id") val shopId: String,
    /** Canonical singular, as the lexicon knows it: "kurti", never "kurtis". */
    val name: String,
    /** What the customer actually called it: "blue kurti". */
    @ColumnInfo(name = "display_name") val displayName: String,
    val quantity: Double? = null,
    val unit: String? = null,
    /** Only when the message stated a rate. Never derived by division. */
    @ColumnInfo(name = "unit_price") val unitPrice: Double? = null,
    /** quantity x unitPrice, only when both were stated. */
    @ColumnInfo(name = "line_total") val lineTotal: Double? = null,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
)
