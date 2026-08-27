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
import android.widget.TextView
import app.vaaniledger.net.Extraction

/**
 * "Yeh sahi hai?" — the confirm sheet.
 *
 * The most important screen in the product: nothing reaches the ledger
 * without a tap here. It shows what was extracted, and shows the confidence
 * PER FIELD, because "the amount is certain but the item is a guess" is the
 * common case and a single blended score hides it.
 *
 * Weak fields are tinted and listed by reason, so the shopkeeper's attention
 * goes to the one field that actually needs it rather than to re-reading the
 * whole entry.
 */
@SuppressLint("ViewConstructor")
class ConfirmSheetView(
    context: Context,
    private val extraction: Extraction,
    private val onConfirm: () -> Unit,
    private val onEdit: () -> Unit,
    private val onDismiss: () -> Unit,
) : LinearLayout(context) {

    private val ink = Color.rgb(0x12, 0x15, 0x1A)
    private val muted = Color.rgb(0x8B, 0x91, 0xA0)
    private val rule = Color.rgb(0xE3, 0xE6, 0xEB)
    private val good = Color.rgb(0x1F, 0x7A, 0x52)
    private val goodSoft = Color.rgb(0xE4, 0xF3, 0xEB)
    private val warn = Color.rgb(0x9A, 0x6A, 0x0C)
    private val warnSoft = Color.rgb(0xFB, 0xF0, 0xDA)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(
                dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
                0f, 0f, 0f, 0f,
            )
        }
        elevation = dp(20).toFloat()

        addView(grabber())
        addView(header())
        addView(quote())
        addView(fields())
        addView(footer())
    }

    // ----------------------------------------------------------------------

    private fun grabber(): View = View(context).apply {
        background = GradientDrawable().apply {
            setColor(rule); cornerRadius = dp(2).toFloat()
        }
        layoutParams = LayoutParams(dp(34), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        }
    }

    private fun header(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(TextView(context).apply {
            text = "Yeh sahi hai?"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        val conf = extraction.confidence
        addView(TextView(context).apply {
            text = "CONFIDENCE %.2f  ·  %s".format(conf.overall, conf.route.uppercase())
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            letterSpacing = 0.06f
            setPadding(0, dp(3), 0, 0)
        })
    }

    /** The original message, so he can check the extraction against it. */
    private fun quote(): View = TextView(context).apply {
        text = "“${extraction.text}”"
        setTextColor(Color.rgb(0x5A, 0x60, 0x70))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setPadding(dp(11), dp(9), dp(11), dp(9))
        background = GradientDrawable().apply {
            setColor(Color.rgb(0xF4, 0xF5, 0xF7))
            cornerRadius = dp(7).toFloat()
        }
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
    }

    private fun fields(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, dp(12), 0, 0)

        val c = extraction.confidence
        row("GRAHAK", extraction.customer ?: "—",
            if (extraction.customerIsNew) "naya grahak" else "khaate mein hai",
            c.fields["customer"]?.score)
        row("RAKAM", extraction.amount?.let { "₹%,.0f".format(it) } ?: "—",
            c.fields["amount"]?.reason, c.fields["amount"]?.score)
        row("SAMAN", extraction.items.joinToString(", ").ifBlank { "—" },
            c.fields["items"]?.reason, c.fields["items"]?.score)
        row("TYPE", extraction.mode.uppercase(),
            c.fields["mode"]?.reason, c.fields["mode"]?.score)
    }

    private fun LinearLayout.row(label: String, value: String, hint: String?, score: Double?) {
        // Below 0.85 the field is worth his attention — same threshold the
        // engine uses to decide auto vs ask, so the UI and the routing can
        // never disagree.
        val weak = score != null && score < 0.85

        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(11), dp(9), dp(11), dp(9))
            if (weak) background = GradientDrawable().apply {
                setColor(warnSoft); cornerRadius = dp(6).toFloat()
            }

            addView(TextView(context).apply {
                text = label
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                letterSpacing = 0.1f
            }, LayoutParams(dp(64), LayoutParams.WRAP_CONTENT))

            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(TextView(context).apply {
                    text = value
                    setTextColor(if (weak) warn else ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                })
                if (!hint.isNullOrBlank()) addView(TextView(context).apply {
                    text = hint
                    setTextColor(muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                })
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            if (score != null) addView(TextView(context).apply {
                text = "%.2f".format(score)
                setTextColor(if (weak) warn else good)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.MONOSPACE
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = GradientDrawable().apply {
                    setColor(if (weak) Color.TRANSPARENT else goodSoft)
                    cornerRadius = dp(10).toFloat()
                }
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(2) })
    }

    private fun footer(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, dp(14), 0, 0)
        addView(button("Badlo", primary = false) { onEdit() }, weight())
        addView(View(context), LayoutParams(dp(9), 1))
        addView(button("Sahi hai  ✓", primary = true) { onConfirm() }, weight())
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): View =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else Color.rgb(0x5A, 0x60, 0x70))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply {
                setColor(if (primary) good else Color.rgb(0xF4, 0xF5, 0xF7))
                cornerRadius = dp(11).toFloat()
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun weight() = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
