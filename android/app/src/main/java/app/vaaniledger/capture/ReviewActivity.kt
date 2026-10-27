package app.vaaniledger.capture

import app.vaaniledger.ui.Theme
import app.vaaniledger.R
import app.vaaniledger.ui.ArtView
import app.vaaniledger.ui.ManuscriptFrame
import app.vaaniledger.ui.Motion
import app.vaaniledger.ui.Scaffold.plainPage
import app.vaaniledger.ui.SealFrame
import app.vaaniledger.ui.Type
import app.vaaniledger.ui.Views.body
import app.vaaniledger.ui.Views.dp
import app.vaaniledger.ui.Views.eyebrow
import app.vaaniledger.ui.Views.pill
import app.vaaniledger.ui.Views.pressable
import app.vaaniledger.ui.Views.text
import app.vaaniledger.ui.Views.title
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

    private var approveAllButton: View? = null

    /** Cards still awaiting a decision, so "approve all" knows what is left. */
    private val outstanding = mutableMapOf<View, ReviewQueue.Pending>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = Strings.of(ShopSettings.language(this)).review

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }

        root.addView(eyebrow(ShopSettings.NAME))
        heading = title("", 30f).apply { setPadding(0, dp(4), 0, 0) }
        root.addView(heading)

        root.addView(text(ReviewQueue.sourceLabel, 14f, muted, Type.displayItalic(this)).apply {
            setPadding(0, dp(2), 0, dp(16))
        })

        val pending = ReviewQueue.take()

        if (pending.isNotEmpty()) {
            approveAllButton = button("✓  ${t.approveAll}", t.approveAllSub(pending.size), primary = true) {
                approveAll()
            }
            root.addView(approveAllButton)
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
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(24), 0, dp(12))
                addView(ArtView(this@ReviewActivity, R.drawable.art_door), LinearLayout.LayoutParams(dp(110), dp(190)))
                addView(text(t.emptyTitle, 20f, ink, Type.display(this@ReviewActivity)).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6))
                })
                addView(body(t.emptySubtitle, 14f).apply { gravity = Gravity.CENTER })
            })
        }

        root.addView(button(t.close, "", primary = false) { finishUp() })

        refreshHeading()
        setContentView(plainPage(root))
        Motion.stagger(list, startDelay = 120, step = 70)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshHeading() {
        heading.text = if (outstanding.isEmpty()) t.allDone else t.newOrder(outstanding.size)
        // Nothing left to approve: an "approve all" button over an empty
        // queue invites a tap that does nothing.
        if (outstanding.isEmpty()) approveAllButton?.visibility = View.GONE
    }

    // ----------------------------------------------------------------------

    private fun card(item: ReviewQueue.Pending): View {
        val e = item.extraction

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = ManuscriptFrame(this@ReviewActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        }

        // Customer and amount, the two things read first.
        val name = e.customer ?: item.customerName ?: "?"
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(text(name.take(1).uppercase(), 18f, indigo, Type.display(this@ReviewActivity)).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Theme.subtle)
                }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            addView(text(name, 18f, ink, Type.medium(this@ReviewActivity)),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // An order with no stated price shows a dash, never a zero.
            // Rs 0 reads as a free sale; "—" reads as what it is, a
            // number nobody has given us yet.
            addView(text(e.amount?.let { "₹%,.0f".format(it) } ?: "—", 24f,
                if (e.amount == null) warn else ink, Type.display(this@ReviewActivity)))
        })

        card.addView(text(itemLine(e), 15f, ink).apply {
            setPadding(dp(52), dp(4), 0, 0)
        })

        badges(e).takeIf { it.isNotEmpty() }?.let { tags ->
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(52), dp(8), 0, 0)
                tags.forEach { (label, colour, fill) ->
                    addView(pill(label, colour, fill), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
                }
            }.let { row ->
                android.widget.HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(row)
                }
            })
        }

        // The evidence. This is the part that makes the card judgeable --
        // set as a quotation, with a gold rule down its margin.
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
            addView(View(this@ReviewActivity).apply { setBackgroundColor(Theme.accent) },
                LinearLayout.LayoutParams(dp(2), -1).apply { marginEnd = dp(12) })
            addView(text(e.text, 14f, muted, Type.displayItalic(this@ReviewActivity)),
                LinearLayout.LayoutParams(0, -2, 1f))
        })

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            addView(
                button("✕  ${t.skip}", t.skipSub, primary = false) { decide(card, approve = false) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(View(this@ReviewActivity), LinearLayout.LayoutParams(dp(10), 1))
            addView(
                button("✓  ${t.save}", t.saveSub, primary = true) { decide(card, approve = true) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f),
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
    private fun badges(e: Extraction): List<Triple<String, Int, Int>> {
        val tags = mutableListOf<Triple<String, Int, Int>>()
        if (e.carriedTopic) tags.add(Triple(t.fromConversation, Theme.oxblood, Theme.warnSoft))
        when (e.mode) {
            "udhaar" -> tags.add(Triple(t.modeCredit, warn, Theme.warnSoft))
            "cash" -> tags.add(Triple(t.modeCash, good, Theme.goodSoft))
            "upi" -> tags.add(Triple(t.modeOnline, Theme.turquoise, Theme.primarySoft))
        }
        if (e.amount == null) tags.add(Triple(t.noPriceYet, warn, Theme.warnSoft))
        if (e.customerIsNew) tags.add(Triple(t.newCustomer, indigo, Theme.primarySoft))
        return tags
    }

    // ----------------------------------------------------------------------

    private fun decide(card: View, approve: Boolean) {
        val item = outstanding.remove(card) ?: return
        card.animate().alpha(0f).translationX(dp(if (approve) 40 else -40).toFloat()).setDuration(220)
            .withEndAction { card.visibility = View.GONE }.start()
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
        setPadding(dp(13), dp(12), dp(13), dp(12))
        background = if (primary) SealFrame(this@ReviewActivity) else GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), ink.withAlpha(0x55))
        }
        pressable()
        setOnClickListener { onClick() }

        addView(text(title, 15.5f, if (primary) Theme.onPrimary else ink, Type.medium(this@ReviewActivity)).apply {
            gravity = Gravity.CENTER
        })
        if (subtitle.isNotBlank()) {
            addView(text(subtitle, 11f, if (primary) Theme.onPrimaryMuted else muted).apply {
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

    companion object {
        private const val TAG = "VaaniReview"
    }
}
