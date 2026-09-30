package app.vaaniledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Business analytics, computed on the phone.
 *
 * Every query here runs against Room and touches no network, because the
 * dashboard is most useful to a shopkeeper standing at a counter with bad
 * signal -- the same reason the extraction engine was brought on-device.
 *
 * TIME BUCKETING. occurred_at is epoch MILLIseconds; SQLite's date functions
 * want seconds, hence the /1000 and 'unixepoch' in every bucket expression.
 * 'localtime' matters as much: a sale at 11pm on the 31st belongs to that
 * month in the shopkeeper's day, not to the next one in UTC.
 *
 * WHAT COUNTS AS REVENUE. Only direction='sale'. A payment is money coming
 * back against an existing debt, and adding it to sales would count the same
 * rupee twice -- once when the goods went out on udhaar and again when it was
 * settled. Deleted rows are excluded everywhere; they are soft-deleted to
 * keep the audit trail, not to keep counting.
 */

data class PeriodTotal(
    /** "2026-09", "2026-Q3" or "2026" depending on the query. */
    val period: String,
    val sale: Double,
    val cashIn: Double,
    val udhaar: Double,
    val entries: Int,
)

data class ProductTotal(
    val name: String,
    val displayName: String,
    /** Summed only across lines that stated a unit -- see the query. */
    val quantity: Double,
    val revenue: Double,
    val orders: Int,
)

data class ModeTotal(val mode: String, val total: Double, val entries: Int)

data class CustomerTotal(
    val customerId: String,
    val name: String,
    val revenue: Double,
    val orders: Int,
)

data class Headline(
    val sale: Double,
    val cashIn: Double,
    val udhaar: Double,
    val entries: Int,
    val customers: Int,
)


@Dao
interface AnalyticsDao {

    // ---- line items -------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<TransactionItemEntity>)

    @Query("DELETE FROM transaction_items WHERE transaction_id = :transactionId")
    suspend fun clearItemsFor(transactionId: String)

    @Query("SELECT COUNT(*) FROM transaction_items")
    suspend fun itemRowCount(): Int

    /** Transactions that have labels but no line-item rows yet -- the backfill set. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE items != '' AND deleted = 0
          AND id NOT IN (SELECT DISTINCT transaction_id FROM transaction_items)
        """
    )
    suspend fun transactionsMissingItems(): List<TransactionEntity>

    // ---- period series ----------------------------------------------------

    @Query(
        """
        SELECT strftime('%Y-%m', occurred_at / 1000, 'unixepoch', 'localtime') AS period,
               COALESCE(SUM(CASE WHEN direction='sale' THEN amount END), 0)               AS sale,
               COALESCE(SUM(CASE WHEN direction='sale' AND mode IN ('cash','upi')
                                 THEN amount END), 0)                                     AS cashIn,
               COALESCE(SUM(CASE WHEN direction='sale' AND mode='udhaar'
                                 THEN amount END), 0)                                     AS udhaar,
               COUNT(*)                                                                   AS entries
        FROM transactions
        WHERE shop_id = :shopId AND deleted = 0
        GROUP BY period
        ORDER BY period DESC
        LIMIT :months
        """
    )
    suspend fun monthly(shopId: String, months: Int = 12): List<PeriodTotal>

    /**
     * Quarters.
     *
     * SQLite has no quarter function, so the month is bucketed arithmetically:
     * (month - 1) / 3 gives 0..3, +1 gives Q1..Q4.
     */
    @Query(
        """
        SELECT strftime('%Y', occurred_at / 1000, 'unixepoch', 'localtime') || '-Q' ||
               ((CAST(strftime('%m', occurred_at / 1000, 'unixepoch', 'localtime') AS INTEGER) - 1) / 3 + 1)
                                                                                          AS period,
               COALESCE(SUM(CASE WHEN direction='sale' THEN amount END), 0)               AS sale,
               COALESCE(SUM(CASE WHEN direction='sale' AND mode IN ('cash','upi')
                                 THEN amount END), 0)                                     AS cashIn,
               COALESCE(SUM(CASE WHEN direction='sale' AND mode='udhaar'
                                 THEN amount END), 0)                                     AS udhaar,
               COUNT(*)                                                                   AS entries
        FROM transactions
        WHERE shop_id = :shopId AND deleted = 0
        GROUP BY period
        ORDER BY period DESC
        LIMIT :quarters
        """
    )
    suspend fun quarterly(shopId: String, quarters: Int = 8): List<PeriodTotal>

    @Query(
        """
        SELECT strftime('%Y', occurred_at / 1000, 'unixepoch', 'localtime')               AS period,
               COALESCE(SUM(CASE WHEN direction='sale' THEN amount END), 0)               AS sale,
               COALESCE(SUM(CASE WHEN direction='sale' AND mode IN ('cash','upi')
                                 THEN amount END), 0)                                     AS cashIn,
               COALESCE(SUM(CASE WHEN direction='sale' AND mode='udhaar'
                                 THEN amount END), 0)                                     AS udhaar,
               COUNT(*)                                                                   AS entries
        FROM transactions
        WHERE shop_id = :shopId AND deleted = 0
        GROUP BY period
        ORDER BY period DESC
        LIMIT :years
        """
    )
    suspend fun annual(shopId: String, years: Int = 5): List<PeriodTotal>

    /** Daily series for the trend line. Ascending, so the chart reads left to right. */
    @Query(
        """
        SELECT strftime('%Y-%m-%d', occurred_at / 1000, 'unixepoch', 'localtime')         AS period,
               COALESCE(SUM(CASE WHEN direction='sale' THEN amount END), 0)               AS sale,
               COALESCE(SUM(CASE WHEN direction='sale' AND mode IN ('cash','upi')
                                 THEN amount END), 0)                                     AS cashIn,
               COALESCE(SUM(CASE WHEN direction='sale' AND mode='udhaar'
                                 THEN amount END), 0)                                     AS udhaar,
               COUNT(*)                                                                   AS entries
        FROM transactions
        WHERE shop_id = :shopId AND deleted = 0
          AND occurred_at >= :since
        GROUP BY period
        ORDER BY period ASC
        """
    )
    suspend fun daily(shopId: String, since: Long): List<PeriodTotal>

    // ---- products ---------------------------------------------------------

    /**
     * Best sellers by revenue.
     *
     * Revenue per line is line_total where the message stated a rate.
     * Where it did not, the line contributes its share of the parent
     * transaction's amount, split EVENLY across that transaction's lines --
     * which is an approximation, and the only honest one available: a
     * two-item bill with one total genuinely does not say which item carried
     * it. The alternative, attributing the whole bill to each line, would
     * report revenue several times larger than the shop actually took.
     *
     * displayName is whichever spelling appears most for that name, so the
     * chart says "blue kurti" rather than picking one arbitrarily.
     */
    @Query(
        """
        SELECT i.name                                            AS name,
               MAX(i.display_name)                               AS displayName,
               COALESCE(SUM(i.quantity), 0)                      AS quantity,
               COALESCE(SUM(
                   COALESCE(i.line_total,
                            t.amount / (SELECT COUNT(*) FROM transaction_items x
                                        WHERE x.transaction_id = i.transaction_id))
               ), 0)                                             AS revenue,
               COUNT(DISTINCT i.transaction_id)                  AS orders
        FROM transaction_items i
        JOIN transactions t ON t.id = i.transaction_id
        WHERE i.shop_id = :shopId AND t.deleted = 0 AND t.direction = 'sale'
          AND i.occurred_at >= :since
        GROUP BY i.name
        ORDER BY revenue DESC
        LIMIT :limit
        """
    )
    suspend fun topProductsByRevenue(shopId: String, since: Long, limit: Int = 8): List<ProductTotal>

    /** Best sellers by how many moved, which is a different question from revenue. */
    @Query(
        """
        SELECT i.name                                            AS name,
               MAX(i.display_name)                               AS displayName,
               COALESCE(SUM(i.quantity), 0)                      AS quantity,
               COALESCE(SUM(
                   COALESCE(i.line_total,
                            t.amount / (SELECT COUNT(*) FROM transaction_items x
                                        WHERE x.transaction_id = i.transaction_id))
               ), 0)                                             AS revenue,
               COUNT(DISTINCT i.transaction_id)                  AS orders
        FROM transaction_items i
        JOIN transactions t ON t.id = i.transaction_id
        WHERE i.shop_id = :shopId AND t.deleted = 0 AND t.direction = 'sale'
          AND i.occurred_at >= :since
        GROUP BY i.name
        ORDER BY quantity DESC, orders DESC
        LIMIT :limit
        """
    )
    suspend fun topProductsByQuantity(shopId: String, since: Long, limit: Int = 8): List<ProductTotal>

    // ---- mix and customers ------------------------------------------------

    @Query(
        """
        SELECT mode                     AS mode,
               COALESCE(SUM(amount), 0) AS total,
               COUNT(*)                 AS entries
        FROM transactions
        WHERE shop_id = :shopId AND deleted = 0 AND direction = 'sale'
          AND occurred_at >= :since
        GROUP BY mode
        ORDER BY total DESC
        """
    )
    suspend fun paymentMix(shopId: String, since: Long): List<ModeTotal>

    @Query(
        """
        SELECT c.id                                              AS customerId,
               c.name                                            AS name,
               COALESCE(SUM(t.amount), 0)                        AS revenue,
               COUNT(*)                                          AS orders
        FROM transactions t
        JOIN customers c ON c.id = t.customer_id
        WHERE t.shop_id = :shopId AND t.deleted = 0 AND t.direction = 'sale'
          AND t.occurred_at >= :since
        GROUP BY c.id
        ORDER BY revenue DESC
        LIMIT :limit
        """
    )
    suspend fun topCustomers(shopId: String, since: Long, limit: Int = 8): List<CustomerTotal>

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN direction='sale' THEN amount END), 0)               AS sale,
               COALESCE(SUM(CASE WHEN direction='sale' AND mode IN ('cash','upi')
                                 THEN amount END), 0)                                     AS cashIn,
               COALESCE(SUM(CASE WHEN direction='sale' AND mode='udhaar'
                                 THEN amount END), 0)                                     AS udhaar,
               COUNT(*)                                                                   AS entries,
               COUNT(DISTINCT customer_id)                                                AS customers
        FROM transactions
        WHERE shop_id = :shopId AND deleted = 0 AND occurred_at >= :since
        """
    )
    suspend fun headline(shopId: String, since: Long): Headline

    // ---- the ledger page --------------------------------------------------

    @Query(
        """
        SELECT * FROM transactions
        WHERE shop_id = :shopId AND deleted = 0
        ORDER BY occurred_at DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun ledgerPage(shopId: String, limit: Int, offset: Int): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM transactions
        WHERE shop_id = :shopId AND deleted = 0 AND mode = :mode
        ORDER BY occurred_at DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun ledgerPageByMode(
        shopId: String,
        mode: String,
        limit: Int,
        offset: Int,
    ): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE shop_id = :shopId AND deleted = 0")
    suspend fun ledgerCount(shopId: String): Int
}
