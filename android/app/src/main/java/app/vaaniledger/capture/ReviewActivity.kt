package app.vaaniledger.capture

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
import android.widget.Toast
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
import app.vaaniledger.net.Extraction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The review queue.
 *
 * Both ingestion lanes end here, and nothing reaches the khata without
 * passing through it. That is the project's rule about money, and it is worth
 * restating because the temptation to skip it is real: the engine is right
 * most of the time, and "most of the time" is not a standard you can apply to
 * someone's ledger.
 *
 * Each card shows THE MESSAGES THE CUSTOMER ACTUALLY SENT, not the tidied
 * sentence the engine parsed. When an order was assembled across turns, the
 * shopkeeper needs to see the turns to judge it -- a resolved order that
 * looks plausible on its own can still be wrong about which item was meant.
 *
 * "Nahi / Skip" is deliberately not a rejection. See [ReviewQueue.approve]
 * for why: only an approved order's messages are ever marked seen, so a
 * skipped one comes back the next time this chat is read, in case the
 * shopkeeper changes their mind.
 */
class ReviewActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val ink = Theme.ink
    private val muted = Theme.muted
    private val indigo = Theme.primary
    private val good = Theme.good
    private val warn = Theme.warn
    private val paper = Theme.paper

    private lateinit var t: Strings.Review
    private lateinit var list: LinearLayout
    private lateinit var heading: TextView

    /** Cards still awaiting a decision, so "approve all" knows what is left. */
    private val outstanding = mutableMapOf<View, ReviewQueue.Pending>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = Strings.of(ShopSettings.language(this)).review

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            setPadding(dp(20), dp(36), dp(20), dp(28))
        }

        heading = TextView(this).apply {
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(heading)

        root.addView(TextView(this).apply {
            text = ReviewQueue.sourceLabel
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(18))
        })

        val pending = ReviewQueue.take()

        if (pending.isNotEmpty()) {
            root.addView(button("✓  ${t.approveAll}", t.approveAllSub(pending.size), primary = true) {
                approveAll()
            })
        }

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(list)

        pending.forEach { item ->
            val card = card(item)
            outstanding[card] = item
            list.addView(card)
        }

        if (pending.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "📭\n\n${t.emptyTitle}\n\n${t.emptySubtitle}"
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(30), 0, 0)
                gravity = Gravity.CENTER
            })
        }

        root.addView(button(t.close, "", primary = false) { finishUp() })

        refreshHeading()
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshHeading() {
        heading.text = if (outstanding.isEmpty()) t.allDone else t.newOrder(outstanding.size)
    }

    // ----------------------------------------------------------------------

    private fun card(item: ReviewQueue.Pending): View {
        val e = item.extraction

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Theme.border)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }

        // Customer and amount, the two things read first.
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(this@ReviewActivity).apply {
                text = "👤"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, 0, dp(8), 0)
            })
            addView(TextView(this@ReviewActivity).apply {
                text = e.customer ?: item.customerName ?: "?"
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(this@ReviewActivity).apply {
                // An order with no stated price shows a dash, never a zero.
                // Rs 0 reads as a free sale; "—" reads as what it is, a
                // number nobody has given us yet.
                text = e.amount?.let { "₹%,.0f".format(it) } ?: "—"
                setTextColor(if (e.amount == null) warn else ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
            })
        })

        card.addView(TextView(this).apply {
            text = itemLine(e)
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(24), dp(6), 0, 0)
        })

        badges(e).takeIf { it.isNotBlank() }?.let { tags ->
            card.addView(TextView(this).apply {
                text = tags
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(24), dp(6), 0, 0)
            })
        }

        // The evidence. This is the part that makes the card judgeable.
        card.addView(TextView(this).apply {
            text = e.text
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(paper)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        })

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(
                button("✓  ${t.save}", t.saveSub, primary = true) { decide(card, approve = true) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(View(this@ReviewActivity), LinearLayout.LayoutParams(dp(8), 1))
            addView(
                button("✕  ${t.skip}", t.skipSub, primary = false) { decide(card, approve = false) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        })

        return card
    }

    /** "3 × blue kurti @ ₹500" -- only the parts that were actually stated. */
    private fun itemLine(e: Extraction): String {
        if (e.lineItems.isEmpty()) return e.items.joinToString(", ").ifBlank { "—" }
        return e.lineItems.joinToString(", ") { li ->
            buildString {
                li.quantity?.let {
                    append(if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString())
                    append(li.unit ?: "")
                    append(" × ")
                }
                append(li.displayName.ifBlank { li.name })
                li.unitPrice?.let { append(" @ ₹%,.0f".format(it)) }
            }
        }
    }

    /**
     * Short tags explaining how the order was put together.
     *
     * The "from conversation" tag is the one that matters: it says the item
     * and the quantity came from different messages, which is precisely the
     * case a shopkeeper should look at twice before approving.
     */
    private fun badges(e: Extraction): String {
        val tags = mutableListOf<String>()
        if (e.carriedTopic) tags.add(t.fromConversation)
        when (e.mode) {
            "udhaar" -> tags.add(t.modeCredit)
            "cash" -> tags.add(t.modeCash)
            "upi" -> tags.add(t.modeOnline)
        }
        if (e.amount == null) tags.add(t.noPriceYet)
        if (e.customerIsNew) tags.add(t.newCustomer)
        return tags.joinToString("  ·  ")
    }

    // ----------------------------------------------------------------------

    private fun decide(card: View, approve: Boolean) {
        val item = outstanding.remove(card) ?: return
        card.visibility = View.GONE
        refreshHeading()

        scope.launch {
            val message = runCatching {
                if (approve) {
                    val tx = ReviewQueue.approve(this@ReviewActivity, item)
                    tx.amount.takeIf { it > 0 }
                        ?.let { t.toastSaved("₹%,.0f".format(it)) }
                        ?: t.toastSavedNoAmount
                } else {
                    t.toastSkipped
                }
            }.getOrElse {
                android.util.Log.e(TAG, "decide failed", it)
                t.toastSaveFailed
            }

            withContext(Dispatchers.Main) { toast(message) }
        }
    }

    private fun approveAll() {
        val all = outstanding.toMap()
        outstanding.clear()
        all.keys.forEach { it.visibility = View.GONE }
        refreshHeading()

        scope.launch {
            var saved = 0
            all.values.forEach { item ->
                runCatching { ReviewQueue.approve(this@ReviewActivity, item) }
                    .onSuccess { saved++ }
                    .onFailure { android.util.Log.e(TAG, "approveAll failed", it) }
            }
            withContext(Dispatchers.Main) { toast(t.toastApprovedCount(saved)) }
        }
    }

    private fun finishUp() {
        ReviewQueue.clear()
        app.vaaniledger.sync.SyncWorker.syncNow(this, ShopSettings.ID, ShopSettings.NAME)
        finish()
    }

    // ----------------------------------------------------------------------

    private fun button(
        title: String,
        subtitle: String,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(13), dp(14), dp(13), dp(14))
        background = GradientDrawable().apply {
            setColor(if (primary) indigo else Theme.subtle)
            cornerRadius = dp(11).toFloat()
        }
        isClickable = true
        setOnClickListener { onClick() }

        addView(TextView(this@ReviewActivity).apply {
            text = title
            setTextColor(if (primary) Color.WHITE else ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        if (subtitle.isNotBlank()) {
            addView(TextView(this@ReviewActivity).apply {
                text = subtitle
                setTextColor(if (primary) Color.argb(180, 255, 255, 255) else muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
            })
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(8)
        layoutParams = lp
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "VaaniReview"
    }
}
