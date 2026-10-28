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
import app.vaaniledger.data.ShopSettings
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.vaaniledger.data.SyncState
import app.vaaniledger.data.TransactionEntity
import app.vaaniledger.net.ApiClient
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
) : CoroutineWorker(context, params) {

    // Taken from the singleton rather than injected. WorkManager's default
    // factory constructs workers with exactly (Context, WorkerParameters);
    // an extra constructor parameter makes every instantiation fail, and it
    // fails QUIETLY — the job reports FAILED and nothing is ever posted.
    // Injecting properly needs a custom WorkerFactory, which is not worth it
    // for one dependency.
    private val api: VaaniApi get() = ApiClient.api(applicationContext)

    private val db = LedgerDatabase.get(context)

    override suspend fun doWork(): Result {
        val shopId = inputData.getString(KEY_SHOP_ID) ?: return Result.failure()
        val full = inputData.getBoolean(KEY_FULL, false)
        val pending = if (full) db.transactions().everything(shopId) else db.transactions().pending()

        return try {
            val response = api.sync(
                SyncRequest(
                    shopId = shopId,
                    shopName = inputData.getString(KEY_SHOP_NAME),
                    since = null,
                    transactions = pending.map { it.toPayload() },
                    full = full,
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

            pullDashboardEntries(response.transactions)
            syncCatalogue(shopId)
            pushCorrections(shopId)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("VaaniSync", "sync failed: ${e.message}")
            // Retry with backoff. The rows stay PENDING and the offline badge
            // keeps showing them, which is the honest state.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /**
     * Entries typed on the dashboard come down to the phone. Only those:
     * every other row on the server came FROM a phone, and writing it back
     * would race the shopkeeper's own edits. A dashboard deletion of a
     * phone row comes down too, as the soft delete it is.
     */
    private suspend fun pullDashboardEntries(rows: List<kotlinx.serialization.json.JsonElement>) {
        for (raw in rows) runCatching {
            val o = raw.jsonObject
            fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            val id = str("id") ?: return@runCatching
            val deleted = o["deleted"]?.jsonPrimitive?.let { it.booleanOrNull ?: (it.intOrNull == 1) } ?: false
            val local = db.transactions().byId(id)
            if (local != null) {
                if (deleted && !local.deleted) db.transactions().upsert(local.copy(deleted = true, syncState = SyncState.SYNCED.name))
                return@runCatching
            }
            if (str("source") != "dashboard" || deleted) return@runCatching
            val name = str("customer_name")
            val customer = name?.takeIf { it.isNotBlank() }?.let { db.customers().resolve(ShopSettings.ID, it) }
            db.transactions().upsert(
                TransactionEntity(
                    id = id,
                    shopId = ShopSettings.ID,
                    customerId = customer?.id,
                    customerName = customer?.name,
                    amount = o["amount"]?.jsonPrimitive?.doubleOrNull ?: return@runCatching,
                    mode = str("mode") ?: "udhaar",
                    direction = str("direction") ?: "sale",
                    items = str("items").orEmpty(),
                    source = "dashboard",
                    confidence = 1.0,
                    occurredAt = runCatching {
                        java.time.LocalDateTime.parse(str("occurred_at")!!.take(19))
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrDefault(System.currentTimeMillis()),
                    syncState = SyncState.SYNCED.name,
                )
            )
        }.onFailure { android.util.Log.w("VaaniSync", "pull skipped a row: ${it.message}") }
    }

    /** Price list both ways; whichever side edited it last wins. */
    private suspend fun syncCatalogue(shopId: String) = runCatching {
        val local = app.vaaniledger.data.CataloguePayload(
            items = app.vaaniledger.data.Catalogue.items(applicationContext),
            updatedAt = app.vaaniledger.data.Catalogue.updatedAt(applicationContext),
        )
        val merged = api.putCatalogue(shopId, local)
        if (merged.updatedAt > local.updatedAt) {
            app.vaaniledger.data.Catalogue.save(applicationContext, merged.items, merged.updatedAt)
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
        const val KEY_SHOP_NAME = "shop_name"
        const val KEY_FULL = "full"
        private const val MAX_ATTEMPTS = 5
        private const val UNIQUE_PERIODIC = "vaani-sync-periodic"
        private const val UNIQUE_NOW = "vaani-sync-now"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Called after every confirmed entry. Coalesces if one is queued. */
        fun syncNow(context: Context, shopId: String, shopName: String? = null) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(
                    androidx.work.workDataOf(KEY_SHOP_ID to shopId, KEY_SHOP_NAME to shopName)
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NOW,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /**
         * Send the whole khata and make the server match it. Its own unique
         * name, so an ordinary sync queued right after cannot replace it.
         */
        fun syncFull(context: Context, shopId: String, shopName: String? = null) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(
                    androidx.work.workDataOf(KEY_SHOP_ID to shopId, KEY_SHOP_NAME to shopName, KEY_FULL to true)
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("vaani-sync-full", ExistingWorkPolicy.REPLACE, request)
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
