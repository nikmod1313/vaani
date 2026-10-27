package app.vaaniledger

import app.vaaniledger.ui.BottomNav
import app.vaaniledger.ui.Nav
import app.vaaniledger.ui.Scaffold.page

import app.vaaniledger.ui.Theme
import app.vaaniledger.ui.ArtView
import app.vaaniledger.ui.LineIcon
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
    /** Set when this page is one customer's account rather than the whole khata. */
    private var customerId: String? = null
    private var customerName: String? = null
    private var balanceCard: LinearLayout? = null
    private var owesStrip: LinearLayout? = null
    /** The search text, trimmed; null when the box is empty. */
    private var query: String? = null
    /**
     * Bumped on every reload. A page that comes back for an older
     * generation -- typing outran the database -- is dropped instead of
     * being appended under results it no longer belongs to.
     */
    private var generation = 0
    private val debounce = android.os.Handler(android.os.Looper.getMainLooper())
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

    private val dayFormat by lazy { SimpleDateFormat("d MMM yyyy", ShopSettings.language(this).locale) }
    private val timeFormat by lazy { SimpleDateFormat("h:mm a", ShopSettings.language(this).locale) }

    private fun strings() = Strings.of(ShopSettings.language(this)).ledger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = strings()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }

        customerId = intent.getStringExtra(EXTRA_CUSTOMER_ID)
        customerName = intent.getStringExtra(EXTRA_CUSTOMER_NAME)

        // The title row: the page's name, and the pen for writing an entry
        // by hand. On a customer's page the title is the customer.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(pageHeader(ShopSettings.name(this@LedgerActivity), customerName ?: s.title),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip("+  ${s.newEntry}", selected = true) { newEntry() }.apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(4)
            })
        })

        countLabel = text("", 13f, muted).apply {
            setPadding(0, dp(4), 0, dp(16))
        }
        root.addView(countLabel)

        if (customerId != null) {
            balanceCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            root.addView(balanceCard, matchWrap(dp(14)))
        } else {
            root.addView(searchBox(), matchWrap(dp(12)))
            owesStrip = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            root.addView(owesStrip)
        }

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

    /**
     * Refresh on every return, not only on creation. The tabs are brought
     * back to the front rather than rebuilt, so a page loaded once kept
     * showing the ledger as it was -- an order approved from the review
     * queue a minute later simply was not there.
     */
    override fun onResume() {
        super.onResume()
        if (resumedOnce) reload() else resumedOnce = true
    }

    private var resumedOnce = false

    override fun onDestroy() {
        debounce.removeCallbacksAndMessages(null)
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
        loadBalances()
        generation++
        loaded = 0
        // Without this the first row of a newly filtered list keeps the
        // previous list's date header, or loses its own.
        lastRenderedDay = null
        loadMoreView = null
        list.removeAllViews()
        loadMore()
    }

    private fun loadMore() {
        val gen = generation
        val mode = filter
        val q = query
        scope.launch {
            val dao = LedgerDatabase.get(this@LedgerActivity).analytics()
            val shop = ShopSettings.ID
            val offset = loaded

            val cust = customerId
            val rows = runCatching {
                dao.ledgerSearch(shop, mode, q, PAGE, offset, cust)
            }.onFailure { android.util.Log.e(TAG, "ledger load failed", it) }
                .getOrDefault(emptyList())

            // Counted under the same filter, so "showing 3 of 3" means three.
            val count = runCatching { dao.ledgerSearchCount(shop, mode, q, cust) }.getOrDefault(0)

            withContext(Dispatchers.Main) {
                if (gen != generation) return@withContext
                total = count
                // Drop the previous page's "show more" before appending,
                // or they stack up down the screen.
                loadMoreView?.let { list.removeView(it) }
                loadMoreView = null

                if (rows.isEmpty() && loaded == 0) {
                    countLabel.text = ""
                    list.addView(emptyState(if (q != null) strings().noMatch(q) else strings().empty))
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
                // The initial opens the customer's own page; the rest of the
                // row still opens the entry for correction.
                val id = tx.customerId
                if (id != null && customerId == null) {
                    pressable()
                    setOnClickListener { openCustomer(id, name) }
                }
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
                        sourceLabel(tx.source),
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

    /** Where an entry came from, in the interface language: the audit trail, readable. */
    private fun sourceLabel(source: String): String {
        val en = ShopSettings.language(this) == app.vaaniledger.data.AppLanguage.EN
        return when (source) {
            "chat", "conversation" -> if (en) "chat" else "चैट"
            "voice" -> if (en) "voice" else "आवाज़"
            "manual" -> if (en) "by hand" else "हाथ से"
            "export" -> if (en) "export" else "एक्सपोर्ट"
            "ocr" -> if (en) "screenshot" else "स्क्रीनशॉट"
            else -> source
        }
    }

    private fun modeLabel(mode: String): String {
        val s = strings()
        return when (mode) {
            "cash" -> s.filterCash
            "upi" -> s.filterUpi
            "udhaar" -> s.filterUdhaar
            else -> mode
        }
    }

    // ---- customers ----------------------------------------------------------

    private fun openCustomer(id: String, name: String) {
        startActivity(
            android.content.Intent(this, LedgerActivity::class.java)
                .putExtra(EXTRA_CUSTOMER_ID, id)
                .putExtra(EXTRA_CUSTOMER_NAME, name)
        )
    }

    /**
     * Balances, computed the same way the bubble's khata does (FIFO aging
     * over every live entry), so the page and the bubble never disagree.
     * On the whole-khata page: a strip of who owes what, largest first. On a
     * customer's page: their balance, and the two things a shopkeeper does
     * about it.
     */
    private fun loadBalances() {
        scope.launch {
            val db = LedgerDatabase.get(this@LedgerActivity)
            val all = runCatching { db.transactions().allOnce(ShopSettings.ID) }.getOrDefault(emptyList())
            val customers = runCatching { db.transactions().customersOnce(ShopSettings.ID) }.getOrDefault(emptyList())
            val balances = app.vaaniledger.data.Aging.balances(all, customers.associate { it.id to it.name })
            val phones = customers.associate { it.id to it.phone }
            withContext(Dispatchers.Main) {
                owesStrip?.let { renderOwes(it, balances.filter { b -> b.outstanding > 0.5 }) }
                balanceCard?.let { card ->
                    val mine = balances.firstOrNull { it.id == customerId }
                    renderBalance(card, mine, phones[customerId])
                }
            }
        }
    }

    private fun renderOwes(strip: LinearLayout, owing: List<app.vaaniledger.data.CustomerBalance>) {
        strip.removeAllViews()
        if (owing.isEmpty()) return
        val s = strings()
        strip.addView(eyebrow(s.whoOwes).apply { setPadding(0, dp(2), 0, dp(8)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        owing.take(12).forEach { b ->
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = app.vaaniledger.ui.ManuscriptFrame(this@LedgerActivity)
                pressable()
                setOnClickListener { openCustomer(b.id, b.name) }
                addView(text(b.name, 14f, ink, Type.medium(this@LedgerActivity)).apply { maxLines = 1 })
                addView(text("₹%,.0f".format(b.outstanding), 18f, ChartPalette.warn, Type.display(this@LedgerActivity)))
                if (b.daysOverdue > 30) addView(text(s.balanceDays(b.daysOverdue), 11f, Theme.danger))
            }, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        }
        strip.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row)
        }, matchWrap(dp(14)))
    }

    private fun renderBalance(
        card: LinearLayout,
        balance: app.vaaniledger.data.CustomerBalance?,
        phone: String?,
    ) {
        val s = strings()
        card.removeAllViews()
        val due = balance?.outstanding ?: 0.0
        card.addView(app.vaaniledger.ui.ArchWindow(
            this, R.drawable.art_palace, arched = false, focusY = 0.9f, shade = 0.9f,
        ).apply {
            addView(LinearLayout(this@LedgerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(18))
                addView(eyebrow(if (due > 0.5) s.owes("") .trim() else s.settled, Theme.gold))
                addView(text("₹%,.0f".format(due), 38f, Theme.onPrimary, Type.display(this@LedgerActivity)))
                if (balance != null && due > 0.5) {
                    addView(text(s.balanceDays(balance.daysOverdue), 13f, Theme.onPrimaryMuted))
                }
            })
        }, matchWrap(dp(12)))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(s.paymentReceived, primary = true) { newEntry(payment = true) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (due > 0.5) {
                addView(View(this@LedgerActivity), LinearLayout.LayoutParams(dp(10), 1))
                addView(button(s.remind, primary = false) { remind(due, phone) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        })
    }

    /** The same reminder the bubble sends: drafted here, sent by the shopkeeper's own tap in WhatsApp. */
    private fun remind(due: Double, phone: String?) {
        val name = customerName ?: return
        val intent = app.vaaniledger.reminder.ReminderLinks.reminderIntent(
            phone = phone,
            customerName = name,
            shopName = ShopSettings.name(this),
            amount = due,
            payeeVpa = ShopSettings.upiId(this),
            language = ShopSettings.language(this).wire,
        )
        if (phone == null) toast(strings().noPhone)
        runCatching { startActivity(intent) }.recoverCatching {
            startActivity(android.content.Intent.createChooser(
                app.vaaniledger.reminder.ReminderLinks.untargetedFallback(intent), strings().remind,
            ))
        }
    }

    /**
     * Write an entry by hand: the edit sheet, with the sale / payment
     * choice shown, prefilled with this customer on a customer's page.
     */
    private fun newEntry(payment: Boolean = false) {
        val s = strings()
        val blank = Extraction(
            text = "",
            customer = customerName,
            amount = null,
            mode = if (payment) "cash" else "udhaar",
            direction = if (payment) "payment" else "sale",
            items = emptyList(),
            confidence = Confidence(overall = 1.0, route = "manual"),
        )
        val dialog = android.app.AlertDialog.Builder(this).create()
        val sheet = EditSheetView(
            context = this,
            extraction = blank,
            onSave = { entry ->
                if (entry.amount == null || entry.amount <= 0) {
                    toast(s.needAmount)
                    return@EditSheetView
                }
                dialog.dismiss()
                scope.launch {
                    runCatching { app.vaaniledger.data.ManualEntry.save(this@LedgerActivity, entry) }
                        .onFailure { android.util.Log.e(TAG, "manual entry failed", it) }
                    app.vaaniledger.sync.SyncWorker.syncNow(this@LedgerActivity, ShopSettings.ID, ShopSettings.name(this@LedgerActivity))
                    withContext(Dispatchers.Main) {
                        toast(s.entrySaved("₹%,.0f".format(entry.amount)))
                        reload()
                    }
                }
            },
            onDismiss = { dialog.dismiss() },
            askDirection = true,
            title = s.newEntryTitle,
        )
        dialog.setView(sheet)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    /**
     * The search field: a rounded paper box with a magnifier, and a clear
     * button once there is something to clear. Results follow the typing
     * after a short pause, so each keystroke is not its own database query.
     */
    private fun searchBox(): View {
        val s = strings()
        val clear = View(this).apply {
            background = LineIcon(this@LedgerActivity, LineIcon.Glyph.CLOSE, muted, sizeDp = 16)
            visibility = View.GONE
            contentDescription = "clear"
        }
        val field = android.widget.EditText(this).apply {
            hint = s.searchHint
            setHintTextColor(muted)
            setTextColor(ink)
            textSize = 15f
            typeface = Type.body(this@LedgerActivity)
            isSingleLine = true
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(10), dp(10), dp(6), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    val text = e?.toString()?.trim().orEmpty()
                    clear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                    debounce.removeCallbacksAndMessages(null)
                    debounce.postDelayed({
                        val next = text.ifEmpty { null }
                        if (next != query) { query = next; reload() }
                    }, 250)
                }
            })
            setOnEditorActionListener { v, _, _ ->
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
                true
            }
        }
        clear.setOnClickListener { field.setText("") }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(Theme.surface)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Theme.border)
            }
            addView(View(this@LedgerActivity).apply {
                background = LineIcon(this@LedgerActivity, LineIcon.Glyph.SEARCH, Theme.accent, sizeDp = 20)
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(clear, LinearLayout.LayoutParams(dp(16), dp(16)))
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
        const val EXTRA_CUSTOMER_ID = "customer_id"
        const val EXTRA_CUSTOMER_NAME = "customer_name"
        const val TAG = "VaaniLedgerPage"
    }
}
