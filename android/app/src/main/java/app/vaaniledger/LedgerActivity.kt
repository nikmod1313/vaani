package app.vaaniledger

import app.vaaniledger.ui.BottomNav
import app.vaaniledger.ui.Nav
import app.vaaniledger.ui.Scaffold.page

import app.vaaniledger.ui.Theme
import app.vaaniledger.ui.ArtView
import app.vaaniledger.ui.OrnamentRule
import app.vaaniledger.ui.Type
import app.vaaniledger.ui.Views.chip
import app.vaaniledger.ui.Views.body
import app.vaaniledger.ui.Views.button
import app.vaaniledger.ui.Views.dp
import app.vaaniledger.ui.Views.eyebrow
import app.vaaniledger.ui.Views.matchWrap
import app.vaaniledger.ui.Views.pageHeader
import app.vaaniledger.ui.Views.pressable
import app.vaaniledger.ui.Views.text
import app.vaaniledger.ui.Views.withAlpha

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
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }

        root.addView(pageHeader(ShopSettings.name(this), s.title))

        countLabel = text("", 13f, muted).apply {
            setPadding(0, dp(4), 0, dp(16))
        }
        root.addView(countLabel)

        filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(filterRow)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
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
                    list.addView(row(tx).also { r ->
                        r.alpha = 0f
                        r.animate().alpha(1f).setDuration(260).start()
                    })
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

    /** A date, as a scribe heads a new day in the register: capitals and a gold rule. */
    private fun dayHeader(day: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(20), 0, dp(4))
        addView(eyebrow(day))
        addView(OrnamentRule(this@LedgerActivity, flourish = false), LinearLayout.LayoutParams(0, dp(8), 1f).apply {
            marginStart = dp(10)
        })
    }

    /**
     * One ruled line of the register.
     *
     * The initial in its medallion is tinted by how the money moved -- green
     * for a payment, saffron for udhaar, indigo for a paid sale -- and the
     * amount carries its sign as well, so the colour is never the only cue.
     */
    private fun row(tx: TransactionEntity): View {
        val s = strings()
        val credit = tx.mode == "udhaar"
        val payment = tx.direction == "payment"
        val name = tx.customerName?.takeIf { it.isNotBlank() } ?: s.noCustomer
        val tone = when {
            payment -> ChartPalette.good
            credit -> ChartPalette.warn
            else -> Theme.primary
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(14), dp(2), dp(14))
            background = RuledLine(this@LedgerActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            pressable()
            setOnClickListener { edit(tx) }

            addView(text(name.take(1).uppercase(), 18f, tone, Type.display(this@LedgerActivity)).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), tone.withAlpha(0x88))
                }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })

            addView(LinearLayout(this@LedgerActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(name, 16f, ink, Type.medium(this@LedgerActivity)))
                addView(text(
                    tx.itemList.joinToString(", ").ifBlank { s.noItems },
                    13.5f, if (tx.items.isBlank()) muted else ink,
                ).apply { maxLines = 2 })
                addView(text(
                    listOf(
                        timeFormat.format(Date(tx.occurredAt)),
                        modeLabel(tx.mode),
                        tx.source,
                        if (tx.syncState == SyncState.SYNCED.name) s.synced else s.pending,
                    ).joinToString("  ·  "),
                    11.5f, muted,
                ).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(text(
                // A payment reduces what is owed, so it reads as a
                // negative against the khata rather than another sale.
                (if (payment) "−" else "+") + "₹%,.0f".format(tx.amount),
                18f, if (payment || credit) tone else ink, Type.display(this@LedgerActivity),
            ).apply { setPadding(dp(10), 0, 0, 0) })
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
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.setButton(
            android.app.AlertDialog.BUTTON_NEUTRAL, s.deleteAction,
        ) { _, _ -> confirmDelete(tx) }
        dialog.show()
        // Deleting is the one destructive action here; it reads as one.
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Theme.danger)
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

    private fun loadMoreButton(): View =
        button(strings().loadMore, primary = false) { loadMore() }.apply {
            layoutParams = matchWrap().apply { topMargin = dp(18) }
        }

    private fun emptyState(text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(36), dp(20), dp(40))
        addView(ArtView(this@LedgerActivity, R.drawable.art_cushions), LinearLayout.LayoutParams(dp(170), dp(150)))
        addView(OrnamentRule(this@LedgerActivity), LinearLayout.LayoutParams(dp(180), dp(14)).apply {
            topMargin = dp(18); bottomMargin = dp(14)
        })
        addView(body(text, 15f).apply { gravity = Gravity.CENTER })
    }

    /** A hairline under each entry, inset from the medallion, like a ruled page. */
    private class RuledLine(context: android.content.Context) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint().apply {
            color = Theme.border
            strokeWidth = context.resources.displayMetrics.density
        }
        private val inset = 56 * context.resources.displayMetrics.density
        override fun draw(canvas: android.graphics.Canvas) {
            val y = bounds.bottom - paint.strokeWidth / 2
            canvas.drawLine(bounds.left + inset, y, bounds.right.toFloat(), y, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val PAGE = 25
        const val TAG = "VaaniLedgerPage"
    }
}
