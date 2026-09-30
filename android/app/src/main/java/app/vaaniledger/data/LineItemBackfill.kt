package app.vaaniledger.data

import android.content.Context

/**
 * Recover line items for transactions saved before the table existed.
 *
 * The v2 -> v3 migration only creates the table. Parsing the old label
 * strings is done here instead, in Kotlin, because a bad regex inside a
 * migration corrupts a ledger with no way back, while a bad regex here
 * produces a wrong analytics row that can be recomputed by clearing one
 * flag.
 *
 * Runs once, then records that it has. Cheap to re-run anyway -- it only
 * looks at transactions that still have no line-item rows -- so the flag is
 * an optimisation, not a correctness guard.
 */
object LineItemBackfill {

    private const val PREFS = "vaani_backfill"
    private const val KEY_DONE = "line_items_v3"

    suspend fun runIfNeeded(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return

        val db = LedgerDatabase.get(context)
        val pending = db.analytics().transactionsMissingItems()
        if (pending.isNotEmpty()) {
            db.analytics().insertItems(pending.flatMap { LineItems.fromLabels(it) })
        }
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }
}
