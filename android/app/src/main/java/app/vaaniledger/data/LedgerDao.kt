package app.vaaniledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data access.
 *
 * Reads that drive UI return Flow so the overlay panel and the ledger screens
 * update themselves after a save -- no manual refresh, and the numbers on the
 * bubble are never stale.
 */

data class CustomerBalance(
    val id: String,
    val name: String,
    val outstanding: Double,
    val lastActivity: Long?,
    val daysOverdue: Int = 0,
)

data class DayTotals(
    val sale: Double,
    val cashIn: Double,
    val udhaar: Double,
    val entries: Int,
    val autoFilled: Int,
)


@Dao
interface ShopDao {
    @Upsert
    suspend fun upsert(shop: ShopEntity)

    @Query("SELECT * FROM shops WHERE id = :shopId")
    suspend fun byId(shopId: String): ShopEntity?

    @Query("SELECT * FROM shops LIMIT 1")
    fun current(): Flow<ShopEntity?>
}


@Dao
interface CustomerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(customer: CustomerEntity): Long

    @Query("SELECT * FROM customers WHERE shop_id = :shopId AND name = :name LIMIT 1")
    suspend fun byName(shopId: String, name: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE shop_id = :shopId ORDER BY name")
    fun all(shopId: String): Flow<List<CustomerEntity>>

    @Query("SELECT name FROM customers WHERE shop_id = :shopId ORDER BY name")
    suspend fun names(shopId: String): List<String>

    /**
     * Get-or-create. The khata builds itself out of transactions; nobody ever
     * types a customer list in.
     */
    @Transaction
    suspend fun resolve(shopId: String, name: String, phone: String? = null): CustomerEntity {
        byName(shopId, name)?.let { return it }
        val created = CustomerEntity(shopId = shopId, name = name, phone = phone)
        insert(created)
        return byName(shopId, name) ?: created
    }
}


@Dao
interface TransactionDao {

    @Upsert
    suspend fun upsert(tx: TransactionEntity)

    @Upsert
    suspend fun upsertAll(transactions: List<TransactionEntity>)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: String): TransactionEntity?

    /**
     * The existing row for this content, if any.
     *
     * Looked up BEFORE writing so a repeat approval updates the row it
     * already made instead of inserting a second one -- and keeps the same
     * transaction id, which is what makes the server sync idempotent too.
     */
    @Query("SELECT * FROM transactions WHERE dedupe_key = :key LIMIT 1")
    suspend fun byDedupeKey(key: String): TransactionEntity?

    /**
     * A live entry with the same customer, amount and direction near this
     * moment -- the same order booked before, reached by another road.
     *
     * The message watermark misses it when the bubbles hash differently the
     * second time (a time label cut off at the screen edge, a reply quoted
     * into a new message) or when the first booking came from the quick
     * confirm sheet. Asking the ledger itself is the last line.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE shop_id = :shopId AND deleted = 0
          AND LOWER(customer_name) = LOWER(:customerName)
          AND ABS(amount - :amount) < 0.5 AND direction = :direction
          AND occurred_at BETWEEN :from AND :to
        LIMIT 1
        """
    )
    suspend fun lookalike(
        shopId: String, customerName: String, amount: Double, direction: String, from: Long, to: Long,
    ): TransactionEntity?

    @Query(
        """
        SELECT * FROM transactions
        WHERE shop_id = :shopId AND deleted = 0
        ORDER BY occurred_at DESC LIMIT :limit
        """
    )
    fun recent(shopId: String, limit: Int = 100): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE customer_id = :customerId AND deleted = 0
        ORDER BY occurred_at DESC
        """
    )
    fun forCustomer(customerId: String): Flow<List<TransactionEntity>>

    /** Chronological, for FIFO settlement. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE shop_id = :shopId AND deleted = 0 AND customer_id IS NOT NULL
        ORDER BY customer_id, occurred_at ASC
        """
    )
    suspend fun allByCustomerChronological(shopId: String): List<TransactionEntity>

    // ---- undo -------------------------------------------------------------

    /**
     * Soft delete only. The row survives so the audit trail stays intact, and
     * the change still syncs -- the server needs to know it was reversed.
     */
    @Query(
        """
        UPDATE transactions SET deleted = 1, sync_state = 'PENDING'
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: String)

    // ---- sync -------------------------------------------------------------

    @Query("SELECT * FROM transactions WHERE sync_state != 'SYNCED' ORDER BY created_at")
    suspend fun pending(): List<TransactionEntity>

    /** Every row, deleted ones too -- the full copy a newly paired computer gets. */
    @Query("SELECT * FROM transactions WHERE shop_id = :shopId ORDER BY created_at")
    suspend fun everything(shopId: String): List<TransactionEntity>

    /** Drives the visible offline queue badge. */
    @Query("SELECT COUNT(*) FROM transactions WHERE sync_state != 'SYNCED'")
    fun pendingCount(): Flow<Int>

    @Query("UPDATE transactions SET sync_state = :state WHERE id IN (:ids)")
    suspend fun markSyncState(ids: List<String>, state: String)

    // ---- aggregates -------------------------------------------------------
    //
    // These mirror the server's queries exactly. If the phone and the
    // dashboard ever disagree on a number, the shopkeeper stops trusting
    // both.

    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN direction='sale' THEN amount END), 0)            AS sale,
          COALESCE(SUM(CASE WHEN direction='sale' AND mode IN ('cash','upi')
                            THEN amount END), 0)                                  AS cashIn,
          COALESCE(SUM(CASE WHEN direction='sale' AND mode='udhaar'
                            THEN amount END), 0)                                  AS udhaar,
          COUNT(*)                                                                AS entries,
          COALESCE(SUM(CASE WHEN confidence >= 0.85 THEN 1 ELSE 0 END), 0)         AS autoFilled
        FROM transactions
        WHERE shop_id = :shopId AND deleted = 0
          AND occurred_at BETWEEN :startOfDay AND :endOfDay
        """
    )
    fun dayTotals(shopId: String, startOfDay: Long, endOfDay: Long): Flow<DayTotals>

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN direction='sale' AND mode='udhaar' THEN amount
                                 WHEN direction='payment' THEN -amount
                                 ELSE 0 END), 0)
        FROM transactions WHERE shop_id = :shopId AND deleted = 0
        """
    )
    fun totalOutstanding(shopId: String): Flow<Double>

    // ---- one-shot reads for the overlay panel -----------------------------
    //
    // The panel is opened, read once and dismissed, so it wants a plain
    // suspend read rather than a Flow it would immediately have to cancel.

    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN direction='sale' THEN amount END), 0)            AS sale,
          COALESCE(SUM(CASE WHEN direction='sale' AND mode IN ('cash','upi')
                            THEN amount END), 0)                                  AS cashIn,
          COALESCE(SUM(CASE WHEN direction='sale' AND mode='udhaar'
                            THEN amount END), 0)                                  AS udhaar,
          COUNT(*)                                                                AS entries,
          COALESCE(SUM(CASE WHEN confidence >= 0.85 THEN 1 ELSE 0 END), 0)         AS autoFilled
        FROM transactions
        WHERE shop_id = :shopId AND deleted = 0
          AND occurred_at BETWEEN :startOfDay AND :endOfDay
        """
    )
    suspend fun dayTotalsOnce(shopId: String, startOfDay: Long, endOfDay: Long): DayTotals

    @Query("SELECT COUNT(*) FROM transactions WHERE sync_state != 'SYNCED'")
    suspend fun pendingCountOnce(): Int

    @Query(
        """
        SELECT * FROM transactions
        WHERE shop_id = :shopId AND deleted = 0
        ORDER BY occurred_at DESC LIMIT :limit
        """
    )
    suspend fun recentOnce(shopId: String, limit: Int = 40): List<TransactionEntity>

    /** Every live transaction, for FIFO aging across all customers. */
    @Query("SELECT * FROM transactions WHERE shop_id = :shopId AND deleted = 0 ORDER BY occurred_at")
    suspend fun allOnce(shopId: String): List<TransactionEntity>

    @Query("SELECT * FROM customers WHERE shop_id = :shopId")
    suspend fun customersOnce(shopId: String): List<CustomerEntity>
}


@Dao
interface CursorDao {
    @Upsert
    suspend fun upsert(cursor: ChatCursorEntity)

    @Query("SELECT * FROM chat_cursors WHERE chat_id = :chatId")
    suspend fun byChat(chatId: String): ChatCursorEntity?
}


@Dao
interface StagedMessageDao {
    /** IGNORE, not REPLACE: the unique content hash is the dedup. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun stage(message: StagedMessageEntity)

    @Query("SELECT * FROM staged_messages WHERE processed = 0 ORDER BY received_at")
    suspend fun unprocessed(): List<StagedMessageEntity>

    @Query("SELECT COUNT(*) FROM staged_messages WHERE processed = 0")
    fun unprocessedCount(): Flow<Int>

    @Query("UPDATE staged_messages SET processed = 1 WHERE id IN (:ids)")
    suspend fun markProcessed(ids: List<String>)
}


@Dao
interface CorrectionDao {
    @Insert
    suspend fun insert(correction: CorrectionEntity)

    @Query("SELECT * FROM corrections WHERE synced = 0")
    suspend fun unsynced(): List<CorrectionEntity>

    @Query("UPDATE corrections SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
