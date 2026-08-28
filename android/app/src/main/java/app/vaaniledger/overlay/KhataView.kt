package app.vaaniledger.overlay

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

    private val ink = Color.rgb(0x12, 0x15, 0x1A)
    private val muted = Color.rgb(0x8B, 0x91, 0xA0)
    private val rule = Color.rgb(0xE3, 0xE6, 0xEB)
    private val saffron = Color.rgb(0xC9, 0x75, 0x2F)
    private val good = Color.rgb(0x1F, 0x7A, 0x52)
    private val cut = Color.rgb(0xB0, 0x3A, 0x2B)

    private val dayFormat = SimpleDateFormat("d MMM, h:mm a", Locale("en", "IN"))

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(
                dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
                0f, 0f, 0f, 0f,
            )
        }
        elevation = dp(20).toFloat()

        addView(header())
        addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(16), 0, dp(16), dp(20))
                if (balances.isEmpty() && recent.isEmpty()) {
                    addView(empty())
                } else {
                    if (balances.any { it.outstanding > 0 }) {
                        addView(sectionLabel("KISKA KITNA BAAKI"))
                        balances.filter { it.outstanding > 0 }.forEach { addView(balanceRow(it)) }
                    }
                    addView(sectionLabel("PICHHLI ENTRIES"))
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
        setPadding(dp(16), dp(14), dp(16), dp(12))

        val total = balances.sumOf { it.outstanding }
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = "Khata"
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = if (total > 0) "Kul baaki ${rupees(total)}" else "Sab clear hai"
                setTextColor(if (total > 0) saffron else good)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(context).apply {
            text = "✕"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { onDismiss() }
        })
    }

    private fun empty(): View = TextView(context).apply {
        text = "Abhi koi entry nahi.\n\nWhatsApp chat kholiye, bubble dabaiye,\naur \"Yeh likh lo\" par tap kijiye."
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(0, dp(50), 0, dp(50))
    }

    private fun sectionLabel(text: String): View = TextView(context).apply {
        this.text = text
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
        letterSpacing = 0.12f
        setPadding(0, dp(14), 0, dp(6))
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.DEFAULT_BOLD
            })
            // Overdue is computed, never stored — a stored value is wrong the
            // next morning.
            if (b.daysOverdue > 30) addView(TextView(context).apply {
                text = "${b.daysOverdue} din purana"
                setTextColor(cut)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(context).apply {
            text = rupees(b.outstanding)
            setTextColor(saffron)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })

        // Assisted reminder: drafts the message and opens WhatsApp with it
        // pre-filled. He taps send himself — nothing leaves the phone without
        // a human seeing it, which for a system that contacts people about
        // money they owe is the right default, not a compromise.
        addView(TextView(context).apply {
            text = "Yaad dilao"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(BubbleView.INDIGO)
                cornerRadius = dp(8).toFloat()
            }
            isClickable = true
            setOnClickListener { onRemind(b) }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(10)
        })
    }

    private fun entryRow(t: TransactionEntity): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, dp(9), 0, dp(9))
        background = GradientDrawable().apply {
            setStroke(dp(1), rule)
            setColor(Color.TRANSPARENT)
        }

        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = t.itemList.joinToString(", ").ifBlank {
                    if (t.direction == "payment") "Payment mila" else "Entry"
                }
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            })
            addView(TextView(context).apply {
                // Source and sync state on every row: the audit trail, and the
                // difference between "not synced yet" and "sync is broken".
                val sync = if (t.syncState == "SYNCED") "" else " · pending"
                text = "${dayFormat.format(Date(t.occurredAt))} · ${t.source}$sync"
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(context).apply {
            val sign = if (t.direction == "payment") "−" else "+"
            text = "$sign${rupees(t.amount)}"
            setTextColor(if (t.direction == "payment") good else if (t.mode == "udhaar") saffron else ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        })
    }

    private fun rupees(v: Double) = "₹%,.0f".format(v)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
