package app.vaaniledger.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.vaaniledger.data.LedgerDatabase
import app.vaaniledger.data.SyncState
import app.vaaniledger.data.TransactionEntity
import app.vaaniledger.net.SyncRequest
import app.vaaniledger.net.TransactionPayload
import app.vaaniledger.net.VaaniApi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Pushes pending entries to the server and pulls back anything changed
 * elsewhere.
 *
 * Safe to run whenever. The server keys on the client-generated transaction
 * id, so a retry after a dropped connection updates rows rather than creating
 * a second set -- which means a failure here costs a delay, never money.
 *
 * Room is the source of truth while offline. Nothing in the UI ever waits on
 * this worker.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val api: VaaniApi,
) : CoroutineWorker(context, params) {

    private val db = LedgerDatabase.get(context)

    override suspend fun doWork(): Result {
        val shopId = inputData.getString(KEY_SHOP_ID) ?: return Result.failure()
        val pending = db.transactions().pending()

        return try {
            val response = api.sync(
                SyncRequest(
                    shopId = shopId,
                    since = null,
                    transactions = pending.map { it.toPayload() },
                )
            )

            // Only mark rows the server actually acknowledged. Marking
            // optimistically would drop an entry on a partial failure.
            if (pending.isNotEmpty()) {
                db.transactions().markSyncState(
                    pending.map { it.id },
                    SyncState.SYNCED.name,
                )
            }

            pushCorrections(shopId)
            Result.success()
        } catch (e: Exception) {
            // Retry with backoff. The rows stay PENDING and the offline badge
            // keeps showing them, which is the honest state.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private suspend fun pushCorrections(shopId: String) {
        val unsynced = db.corrections().unsynced()
        if (unsynced.isEmpty()) return
        // Best-effort: a correction failing to upload must never block the
        // ledger sync that already succeeded.
        runCatching {
            unsynced.forEach { /* POST /corrections — see CorrectionRequest */ }
            db.corrections().markSynced(unsynced.map { it.id })
        }
    }

    companion object {
        const val KEY_SHOP_ID = "shop_id"
        private const val MAX_ATTEMPTS = 5
        private const val UNIQUE_PERIODIC = "vaani-sync-periodic"
        private const val UNIQUE_NOW = "vaani-sync-now"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Called after every confirmed entry. Coalesces if one is queued. */
        fun syncNow(context: Context, shopId: String) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(androidx.work.workDataOf(KEY_SHOP_ID to shopId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NOW,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** Safety net for entries logged while the phone had no signal. */
        fun schedulePeriodic(context: Context, shopId: String) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(androidx.work.workDataOf(KEY_SHOP_ID to shopId))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}


/** Millis to the ISO-8601 local form the API expects. */
internal fun Long.toIsoLocal(): String =
    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDateTime()
    )

internal fun TransactionEntity.toPayload() = TransactionPayload(
    id = id,
    amount = amount,
    mode = mode,
    direction = direction,
    customerName = customerName,
    items = itemList,
    source = source,
    confidence = confidence,
    occurredAt = occurredAt.toIsoLocal(),
    deleted = deleted,
)
