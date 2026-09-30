package app.vaaniledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.TimeUnit

@Database(
    entities = [
        ShopEntity::class,
        CustomerEntity::class,
        TransactionEntity::class,
        ChatCursorEntity::class,
        StagedMessageEntity::class,
        CorrectionEntity::class,
        TransactionItemEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun shops(): ShopDao
    abstract fun customers(): CustomerDao
    abstract fun transactions(): TransactionDao
    abstract fun cursors(): CursorDao
    abstract fun staged(): StagedMessageDao
    abstract fun corrections(): CorrectionDao
    abstract fun analytics(): AnalyticsDao

    companion object {
        @Volatile private var instance: LedgerDatabase? = null

        /**
         * v1 -> v2: shops gain a category.
         *
         * Written rather than falling back to a destructive migration,
         * because v1 databases on real phones hold entries that were never
         * synced -- dropping the file to add one column would delete money
         * the server has never seen.
         *
         * NOT NULL with a DEFAULT so every existing row is valid the moment
         * the column appears; without the default the ALTER fails outright on
         * a table that already has rows.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE shops ADD COLUMN category TEXT NOT NULL DEFAULT 'general'"
                )
            }
        }

        /**
         * v2 -> v3: line items get their own table.
         *
         * Creates the table only. Existing transactions keep their flattened
         * label string and are backfilled from it by [LineItemBackfill] on
         * first use -- parsing belongs in Kotlin where it is testable, not in
         * a migration where a bad regex corrupts a ledger with no way back.
         *
         * Index names and the exact column spellings must match what Room
         * generates from the entity, or validation fails at open time on a
         * device that has already migrated.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transaction_items` (
                        `id` TEXT NOT NULL,
                        `transaction_id` TEXT NOT NULL,
                        `shop_id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `quantity` REAL,
                        `unit` TEXT,
                        `unit_price` REAL,
                        `line_total` REAL,
                        `occurred_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`transaction_id`) REFERENCES `transactions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transaction_items_transaction_id` " +
                        "ON `transaction_items` (`transaction_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transaction_items_shop_id_occurred_at` " +
                        "ON `transaction_items` (`shop_id`, `occurred_at`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transaction_items_shop_id_name` " +
                        "ON `transaction_items` (`shop_id`, `name`)"
                )
            }
        }

        /**
         * v3 -> v4: transactions gain a content-derived identity.
         *
         * Existing rows are left NULL rather than being back-filled. SQLite
         * treats NULLs as distinct in a unique index, so history keeps
         * working untouched while every new entry is protected. Back-filling
         * would mean computing keys for rows whose source messages are long
         * gone, and two old entries that happened to collide would be merged
         * -- silently deleting a real sale to fix a duplicate that was never
         * there.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN dedupe_key TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_dedupe_key` " +
                        "ON `transactions` (`dedupe_key`)"
                )
            }
        }

        fun get(context: Context): LedgerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    "vaaniledger.db",
                )
                    // No fallbackToDestructiveMigration. This database is the
                    // shopkeeper's money; a schema change must migrate it, not
                    // drop it. Write the Migration before bumping version.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}


/**
 * Khata aging.
 *
 * Payments settle debts oldest-first, and the age of a balance is the age of
 * the oldest debt still uncovered.
 *
 * THIS MUST MATCH `backend/app/store.py::outstanding` EXACTLY. Taking the
 * oldest udhaar date outright ages a balance from a debt already paid off, so
 * every regular customer reads as permanently overdue and the flag stops
 * meaning anything -- and the reminder it drives is plainly wrong to whoever
 * receives it. That bug was caught on the server; do not reintroduce it here.
 *
 * If the phone and the dashboard ever disagree about how many days someone is
 * overdue, the shopkeeper stops trusting both.
 */
object Aging {

    private val DAY_MS = TimeUnit.DAYS.toMillis(1)

    data class Balance(val outstanding: Double, val daysOverdue: Int, val lastActivity: Long?)

    /**
     * @param chronological one customer's live transactions, oldest first.
     */
    fun settle(chronological: List<TransactionEntity>, now: Long = System.currentTimeMillis()): Balance {
        // Each entry is [occurredAt, remaining], oldest first.
        val debts = ArrayDeque<Pair<Long, Double>>()
        var lastActivity: Long? = null

        for (tx in chronological) {
            lastActivity = maxOf(lastActivity ?: 0L, tx.occurredAt)

            when {
                tx.direction == Direction.SALE.wire && tx.mode == PaymentMode.UDHAAR.wire ->
                    debts.addLast(tx.occurredAt to tx.amount)

                tx.direction == Direction.PAYMENT.wire -> {
                    var credit = tx.amount
                    while (credit > 0 && debts.isNotEmpty()) {
                        val (at, remaining) = debts.first()
                        if (remaining <= credit) {
                            credit -= remaining
                            debts.removeFirst()
                        } else {
                            debts.removeFirst()
                            debts.addFirst(at to (remaining - credit))
                            credit = 0.0
                        }
                    }
                    // Overpayment is not modelled: the balance floors at zero
                    // rather than carrying an advance. Same as the server.
                }
            }
        }

        val outstanding = debts.sumOf { it.second }
        val days = if (outstanding > 0 && debts.isNotEmpty()) {
            ((now - debts.first().first) / DAY_MS).toInt()
        } else {
            0
        }
        return Balance(outstanding, days, lastActivity)
    }

    /** Group a shop's transactions by customer and settle each. */
    fun balances(
        transactions: List<TransactionEntity>,
        customerNames: Map<String, String>,
        now: Long = System.currentTimeMillis(),
    ): List<CustomerBalance> =
        transactions
            .groupBy { it.customerId }
            .mapNotNull { (customerId, rows) ->
                if (customerId == null) return@mapNotNull null
                val balance = settle(rows.sortedBy { it.occurredAt }, now)
                CustomerBalance(
                    id = customerId,
                    name = customerNames[customerId] ?: "?",
                    outstanding = balance.outstanding,
                    lastActivity = balance.lastActivity,
                    daysOverdue = balance.daysOverdue,
                )
            }
            .sortedWith(compareByDescending<CustomerBalance> { it.outstanding }.thenBy { it.name })
}
