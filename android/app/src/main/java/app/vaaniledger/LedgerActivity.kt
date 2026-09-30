package app.vaaniledger

import app.vaaniledger.ui.BottomNav
import app.vaaniledger.ui.Nav
import app.vaaniledger.ui.Scaffold.page

import app.vaaniledger.ui.Theme

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.vaaniledger.data.LedgerDatabase
import app.vaaniledger.data.LineItems
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
import app.vaaniledger.data.SyncState
import app.vaaniledger.data.TransactionEntity
import app.vaaniledger.net.Confidence
import app.vaaniledger.net.Extraction
import app.vaaniledger.overlay.EditSheetView
import app.vaaniledger.ui.ChartPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The full ledger -- every entry, oldest to newest, with its provenance.
 *
 * The overlay's Khata answers "who owes me what". This answers the different
 * question of "what exactly did I record, and where did it come from", which
 * is what a shopkeeper needs when a customer disputes a figure. So each row
 * shows the items, the mode, how the entry was captured, and whether it has
 * reached the server yet -- the audit trail the README asks every entry to
 * carry.
 *
 * Paged rather than loaded whole: a shop three years in has thousands of
 * rows, and reading them all to show twenty is how a page takes four seconds
 * to open on the cheap phone this is aimed at.
 */
class LedgerActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val paper = Theme.paper
    private val ink = ChartPalette.ink
    private val muted = ChartPalette.muted
    private val indigo = ChartPalette.indigo

    private lateinit var list: LinearLayout
    private lateinit var filterRow: LinearLayout
    private lateinit var countLabel: TextView

    private var filter: String? = null
    private var loaded = 0
    private var total = 0

    /** The date header last written, so paging does not repeat or drop one. */
    private var lastRenderedDay: String? = null

    /**
     * The paging button, held rather than tagged.
     *
     * View.setTag(int, ..) demands a real resource id and throws for anything
     * else, so there is no free integer key to find this view by later.
     */
    private var loadMoreView: View? = null

    private val dayFormat = SimpleDateFormat("d MMM yyyy", Locale("en", "IN"))
    private val timeFormat = SimpleDateFormat("h:mm a", Locale("en", "IN"))

    private fun strings() = Strings.of(ShopSettings.language(this)).ledger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = strings()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(32), dp(18), dp(28))
        }

        root.addView(TextView(this).apply {
            text = s.title
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
        })

        countLabel = TextView(this).apply {
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), 0, dp(14))
        }
        root.addView(countLabel)

        filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(filterRow)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(list)

        renderFilters()
        setContentView(
            page(
                current = BottomNav.Destination.LEDGER,
                items = Nav.items(this),
                onNavigate = { Nav.go(this, it) },
                content = root,
            )
        )
        reload()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------------------

    private fun renderFilters() {
        val s = strings()
        filterRow.removeAllViews()
        listOf(
            null to s.filterAll,
            "udhaar" to s.filterUdhaar,
            "cash" to s.filterCash,
            "upi" to s.filterUpi,
        ).forEach { (mode, label) ->
            filterRow.addView(chip(label, mode == filter) {
                filter = mode
                renderFilters()
                reload()
            })
        }
    }

    private fun reload() {
        loaded = 0
        // Without this the first row of a newly filtered list keeps the
        // previous list's date header, or loses its own.
        lastRenderedDay = null
        loadMoreView = null
        list.removeAllViews()
        loadMore()
    }

    private fun loadMore() {
        scope.launch {
            val dao = LedgerDatabase.get(this@LedgerActivity).analytics()
            val shop = ShopSettings.ID
            val offset = loaded

            val rows = runCatching {
                val mode = filter
                if (mode == null) dao.ledgerPage(shop, PAGE, offset)
                else dao.ledgerPageByMode(shop, mode, PAGE, offset)
            }.onFailure { android.util.Log.e(TAG, "ledger load failed", it) }
                .getOrDefault(emptyList())

            val count = runCatching { dao.ledgerCount(shop) }.getOrDefault(0)

            withContext(Dispatchers.Main) {
                total = count
                // Drop the previous page's "show more" before appending,
                // or they stack up down the screen.
                loadMoreView?.let { list.removeView(it) }
                loadMoreView = null

                if (rows.isEmpty() && loaded == 0) {
                    countLabel.text = ""
                    list.addView(emptyState(strings().empty))
                    return@withContext
                }

                var lastDay: String? = lastRenderedDay
                rows.forEach { tx ->
                    val day = dayFormat.format(Date(tx.occurredAt))
                    if (day != lastDay) {
                        list.addView(dayHeader(day))
                        lastDay = day
                    }
                    list.addView(row(tx))
                }
                lastRenderedDay = lastDay
                loaded += rows.size

                countLabel.text = strings().showing(loaded, total) + " · " + strings().tapToEdit
                if (loaded < total && rows.size == PAGE) {
                    loadMoreButton().also { loadMoreView = it; list.addView(it) }
                }
            }
        }
    }

    // ----------------------------------------------------------------------

    private fun dayHeader(day: String): View = TextView(this).apply {
        text = day.uppercase()
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(2), dp(12), 0, dp(6))
    }

    private fun row(tx: TransactionEntity): View {
        val s = strings()
        val credit = tx.mode == "udhaar"
        val payment = tx.direction == "payment"

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), Theme.border)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            isClickable = true
            setOnClickListener { edit(tx) }

            addView(LinearLayout(this@LedgerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@LedgerActivity).apply {
                    text = tx.customerName?.takeIf { it.isNotBlank() } ?: s.noCustomer
                    setTextColor(ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                addView(TextView(this@LedgerActivity).apply {
                    // A payment reduces what is owed, so it reads as a
                    // negative against the khata rather than another sale.
                    text = (if (payment) "−" else "+") + "₹%,.0f".format(tx.amount)
                    setTextColor(
                        when {
                            payment -> ChartPalette.good
                            credit -> ChartPalette.warn
                            else -> ink
                        }
                    )
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                })
            })

            addView(TextView(this@LedgerActivity).apply {
                text = tx.itemList.joinToString(", ").ifBlank { s.noItems }
                setTextColor(if (tx.items.isBlank()) muted else ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, 0)
            })

            addView(TextView(this@LedgerActivity).apply {
                val synced = tx.syncState == SyncState.SYNCED.name
                text = listOf(
                    timeFormat.format(Date(tx.occurredAt)),
                    modeLabel(tx.mode),
                    tx.source,
                    if (synced) s.synced else s.pending,
                ).joinToString(" · ")
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    /**
     * Correct or remove a saved entry.
     *
     * The engine gets things wrong, and until now a wrong entry was permanent
     * -- the review queue could refuse one, but nothing could fix one already
     * in the khata. A shopkeeper who cannot correct their own ledger stops
     * trusting it.
     *
     * The transaction ID and its dedupe key are both preserved, so an edit
     * updates the same row here AND the same row on the server, rather than
     * arriving there as a second sale.
     */
    private fun edit(tx: TransactionEntity) {
        val s = strings()
        val asExtraction = Extraction(
            text = tx.itemList.joinToString(", "),
            customer = tx.customerName,
            amount = tx.amount.takeIf { it > 0 },
            mode = tx.mode,
            direction = tx.direction,
            items = tx.itemList,
            confidence = Confidence(overall = 1.0, route = "manual"),
        )

        val dialog = android.app.AlertDialog.Builder(this).create()
        val sheet = EditSheetView(
            context = this,
            extraction = asExtraction,
            onSave = { corrected ->
                dialog.dismiss()
                applyEdit(tx, corrected)
            },
            onDismiss = { dialog.dismiss() },
        )
        dialog.setView(sheet)
        dialog.setButton(
            android.app.AlertDialog.BUTTON_NEUTRAL, s.deleteAction,
        ) { _, _ -> confirmDelete(tx) }
        dialog.show()
    }

    private fun applyEdit(tx: TransactionEntity, corrected: EditSheetView.Corrected) {
        scope.launch {
            val db = LedgerDatabase.get(this@LedgerActivity)
            runCatching {
                val customer = corrected.customer?.takeIf { it.isNotBlank() }
                    ?.let { db.customers().resolve(ShopSettings.ID, it) }

                val updated = tx.copy(
                    customerId = customer?.id ?: tx.customerId,
                    customerName = customer?.name ?: tx.customerName,
                    amount = corrected.amount ?: tx.amount,
                    mode = corrected.mode,
                    items = corrected.items.joinToString(","),
                    // Human-corrected, and it has to go up again.
                    confidence = 1.0,
                    syncState = SyncState.PENDING.name,
                )
                db.transactions().upsert(updated)

                // The line items are derived from the labels the human just
                // edited, so the analytics page agrees with the ledger.
                db.analytics().clearItemsFor(updated.id)
                db.analytics().insertItems(LineItems.fromLabels(updated))
            }.onFailure { android.util.Log.e(TAG, "edit failed", it) }

            withContext(Dispatchers.Main) {
                toast(strings().updated)
                reload()
            }
        }
    }

    private fun confirmDelete(tx: TransactionEntity) {
        val s = strings()
        android.app.AlertDialog.Builder(this)
            .setMessage(s.deleteConfirm)
            .setNegativeButton(s.cancel, null)
            .setPositiveButton(s.deleteAction) { _, _ ->
                scope.launch {
                    // Soft delete: the row survives so the audit trail stays
                    // intact and the reversal still syncs -- the server needs
                    // to know it was reversed, not just stop hearing about it.
                    runCatching {
                        LedgerDatabase.get(this@LedgerActivity).transactions().softDelete(tx.id)
                    }.onFailure { android.util.Log.e(TAG, "delete failed", it) }
                    withContext(Dispatchers.Main) {
                        toast(s.deleted)
                        reload()
                    }
                }
            }
            .show()
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()

    private fun modeLabel(mode: String): String {
        val s = strings()
        return when (mode) {
            "cash" -> s.filterCash
            "upi" -> s.filterUpi
            "udhaar" -> s.filterUdhaar
            else -> mode
        }
    }

    private fun loadMoreButton(): View = TextView(this).apply {
        text = strings().loadMore
        setTextColor(indigo)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(11).toFloat()
            setStroke(dp(1), Theme.border)
        }
        isClickable = true
        setOnClickListener { loadMore() }
    }

    private fun emptyState(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(50), dp(20), dp(50))
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): View =
        TextView(this).apply {
            text = label
            setTextColor(if (selected) Color.WHITE else ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(if (selected) indigo else Color.WHITE)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), if (selected) indigo else Theme.border)
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(8) }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGE = 25
        const val TAG = "VaaniLedgerPage"
    }
}
