package app.vaaniledger.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole khata as a spreadsheet, handed to the share sheet.
 *
 * The ledger lives on the phone, which means a lost or broken phone loses
 * it -- and with it every rupee every customer owes. The server copy is
 * optional and most shops will never run one. This is the backup a
 * shopkeeper can actually make: one tap, and the file goes wherever they
 * already keep things -- to themselves on WhatsApp, to Drive, to a
 * relative's email.
 *
 * Nothing is uploaded by this code. The share sheet decides where the file
 * goes, and only because the shopkeeper chose it; the file sits in the app's
 * private cache and is reachable only through the one grant the share
 * intent carries.
 *
 * CSV rather than anything richer because every spreadsheet app opens it,
 * and with a UTF-8 byte-order mark so Excel shows Devanagari names as
 * Devanagari instead of mojibake.
 */
object KhataExport {

    private val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val day = SimpleDateFormat("dd-MM-yyyy", Locale.US)
    private val time = SimpleDateFormat("HH:mm", Locale.US)

    /** Write the file and return a share intent for it. */
    suspend fun build(context: Context, shopId: String, shopName: String): Intent {
        val rows = LedgerDatabase.get(context).transactions().allOnce(shopId)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // One file per day, overwritten: re-exporting should not pile up copies.
        val file = File(dir, "vaani-khata-${stamp.format(Date())}.csv")
        file.writeText(csv(rows), Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "$shopName — khata ${day.format(Date())}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * One row per entry, oldest first, with the amount SIGNED the way the
     * khata reads it: a payment reduces what is owed, so it is negative.
     * Summing the Amount column for one customer gives what they owe.
     */
    internal fun csv(rows: List<TransactionEntity>): String = buildString {
        append('﻿')
        appendLine("Date,Time,Customer,Type,Mode,Amount,Items,Source,Synced")
        for (t in rows) {
            val signed = if (t.direction == "payment") -t.amount else t.amount
            appendLine(
                listOf(
                    day.format(Date(t.occurredAt)),
                    time.format(Date(t.occurredAt)),
                    t.customerName.orEmpty(),
                    t.direction,
                    t.mode,
                    "%.2f".format(Locale.US, signed),
                    t.itemList.joinToString("; "),
                    t.source,
                    if (t.syncState == SyncState.SYNCED.name) "yes" else "no",
                ).joinToString(",") { field(it) }
            )
        }
    }

    /** RFC 4180 quoting, so a customer named "Verma, R." stays in one cell. */
    private fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\""
        else value
}
