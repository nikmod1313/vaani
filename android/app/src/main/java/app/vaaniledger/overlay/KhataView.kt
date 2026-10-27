package app.vaaniledger.overlay

import app.vaaniledger.ui.SealFrame
import app.vaaniledger.ui.SheetBackground
import app.vaaniledger.ui.Theme
import app.vaaniledger.ui.Type
import app.vaaniledger.ui.Views.pressable
import app.vaaniledger.ui.Views.withAlpha

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.vaaniledger.data.CustomerBalance
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
import app.vaaniledger.data.TransactionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The khata: who owes what, and every entry behind it.
 *
 * Balances first because that is the question he opens this to answer, then
 * the entries that produced them. Each entry carries its source tag — chat,
 * voice, ocr, manual — which is the audit trail made visible, and the thing
 * that lets him check an amount he does not recognise.
 */
@SuppressLint("ViewConstructor")
class KhataView(
    context: Context,
    private val balances: List<CustomerBalance>,
    private val recent: List<TransactionEntity>,
    private val onDismiss: () -> Unit,
    private val onRemind: (CustomerBalance) -> Unit = {},
) : LinearLayout(context) {

    private val t = Strings.of(ShopSettings.language(context)).sheets
    private val strings get() = t
    private val ink = Theme.ink
    private val muted = Theme.muted
    private val rule = Theme.border
    private val saffron = Theme.accent
    private val good = Theme.good
    private val cut = Theme.danger

    private val dayFormat = SimpleDateFormat("d MMM, h:mm a", Locale("en", "IN"))

    init {
        orientation = VERTICAL
        background = SheetBackground(context)
        elevation = dp(20).toFloat()

        addView(header())
        addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(20), 0, dp(20), dp(34))
                if (balances.isEmpty() && recent.isEmpty()) {
                    addView(empty())
                } else {
                    if (balances.any { it.outstanding > 0 }) {
                        addView(sectionLabel(t.oweHeading))
                        balances.filter { it.outstanding > 0 }.forEach { addView(balanceRow(it)) }
                    }
                    addView(sectionLabel(t.recentHeading))
                    recent.forEach { addView(entryRow(it)) }
                }
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(420)))

        // Dismiss on an outside tap. Without this the only way out is the ✕,
        // because the bubble is hidden while a sheet is up — one missed tap
        // and the sheet is stuck on screen over whatever app is behind it.
        setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                onDismiss(); true
            } else false
        }
    }

    // ----------------------------------------------------------------------

    private fun header(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(8))

        val total = balances.sumOf { it.outstanding }
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = t.khataTitle
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                typeface = Type.display(context)
            })
            addView(TextView(context).apply {
                text = if (total > 0) t.totalDue(rupees(total)) else t.allClear
                setTextColor(if (total > 0) Theme.warn else good)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Type.displayItalic(context)
            })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(context).apply {
            text = "✕"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { onDismiss() }
        })
    }

    private fun empty(): View = TextView(context).apply {
        text = t.khataEmpty
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Type.displayItalic(context)
        gravity = Gravity.CENTER
        setPadding(0, dp(50), 0, dp(50))
    }

    private fun sectionLabel(text: String): View = TextView(context).apply {
        this.text = text
        setTextColor(Theme.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        typeface = Type.bold(context)
        letterSpacing = 0.14f
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun balanceRow(b: CustomerBalance): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(9), 0, dp(9))

        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = b.name
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Type.medium(context)
            })
            // Overdue is computed, never stored — a stored value is wrong the
            // next morning.
            if (b.daysOverdue > 30) addView(TextView(context).apply {
                text = t.daysOld(b.daysOverdue)
                setTextColor(cut)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Type.body(context)
            })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(context).apply {
            text = rupees(b.outstanding)
            setTextColor(Theme.warn)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Type.display(context)
        })

        // Assisted reminder: drafts the message and opens WhatsApp with it
        // pre-filled. He taps send himself — nothing leaves the phone without
        // a human seeing it, which for a system that contacts people about
        // money they owe is the right default, not a compromise.
        addView(TextView(context).apply {
            text = t.remind
            setTextColor(Theme.onPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Type.medium(context)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = SealFrame(context, radius = dp(16).toFloat())
            pressable()
            setOnClickListener { onRemind(b) }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(10)
        })
    }

    private fun entryRow(e: TransactionEntity): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, dp(9), 0, dp(9))
        background = GradientDrawable().apply {
            setStroke(dp(1), Color.TRANSPARENT)
            setColor(Color.TRANSPARENT)
        }

        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = e.itemList.joinToString(", ").ifBlank {
                    if (e.direction == "payment") strings.paymentReceived else strings.entry
                }
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Type.body(context)
            })
            addView(TextView(context).apply {
                // Source and sync state on every row: the audit trail, and the
                // difference between "not synced yet" and "sync is broken".
                val sync = if (e.syncState == "SYNCED") "" else " · ${strings.pending}"
                text = "${dayFormat.format(Date(e.occurredAt))} · ${e.source}$sync"
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Type.body(context)
            })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(context).apply {
            val sign = if (e.direction == "payment") "−" else "+"
            text = "$sign${rupees(e.amount)}"
            setTextColor(if (e.direction == "payment") good else if (e.mode == "udhaar") Theme.warn else ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Type.display(context)
        })
    }

    private fun rupees(v: Double) = "₹%,.0f".format(v)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
