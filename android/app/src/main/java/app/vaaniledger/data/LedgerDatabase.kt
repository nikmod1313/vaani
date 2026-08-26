package app.vaaniledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.TimeUnit

@Database(
    entities = [
        ShopEntity::class,
        CustomerEntity::class,
        TransactionEntity::class,
        ChatCursorEntity::class,
        StagedMessageEntity::class,
        CorrectionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun shops(): ShopDao
    abstract fun customers(): CustomerDao
    abstract fun transactions(): TransactionDao
    abstract fun cursors(): CursorDao
    abstract fun staged(): StagedMessageDao
    abstract fun corrections(): CorrectionDao

    companion object {
        @Volatile private var instance: LedgerDatabase? = null

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
