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

    private val ink = Theme.ink
    private val muted = Theme.muted
    private val rule = Theme.border
    private val good = Theme.good
    private val goodSoft = Theme.goodSoft
    private val warn = Theme.warn
    private val warnSoft = Theme.warnSoft

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(34))
        background = SheetBackground(context)
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
            setColor(Theme.accent.withAlpha(0x88)); cornerRadius = dp(2).toFloat()
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Type.display(context)
        })
        val conf = extraction.confidence
        addView(TextView(context).apply {
            text = "CONFIDENCE %.2f  ·  %s".format(conf.overall, conf.route.uppercase())
            setTextColor(Theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Type.bold(context)
            letterSpacing = 0.12f
            setPadding(0, dp(3), 0, 0)
        })
    }

    /** The original message, so he can check the extraction against it -- set as a quotation. */
    private fun quote(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(View(context).apply { setBackgroundColor(Theme.accent) },
            LayoutParams(dp(2), LayoutParams.MATCH_PARENT).apply { marginEnd = dp(12) })
        addView(TextView(context).apply {
            text = "“${extraction.text}”"
            setTextColor(Theme.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Type.displayItalic(context)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(14) }
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
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                if (weak) setColor(warnSoft) else setColor(Color.TRANSPARENT)
                cornerRadius = dp(8).toFloat()
                if (!weak) setStroke(0, Color.TRANSPARENT)
            }

            addView(TextView(context).apply {
                text = label
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Type.bold(context)
                letterSpacing = 0.12f
                setPadding(0, dp(4), 0, 0)
            }, LayoutParams(dp(70), LayoutParams.WRAP_CONTENT))

            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(TextView(context).apply {
                    text = value
                    setTextColor(if (weak) warn else ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    typeface = Type.display(context)
                })
                if (!hint.isNullOrBlank()) addView(TextView(context).apply {
                    text = hint
                    setTextColor(muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Type.body(context)
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
        setPadding(0, dp(16), 0, 0)
        addView(button("Badlo", primary = false) { onEdit() }, weight())
        addView(View(context), LayoutParams(dp(10), 1))
        addView(button("Sahi hai  ✓", primary = true) { onConfirm() }, weight())
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): View =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (primary) Theme.onPrimary else ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Type.medium(context)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            // Confirm is the seal in green: money is about to be written down.
            background = if (primary) SealFrame(context, fill = good) else GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), ink.withAlpha(0x55))
            }
            pressable()
            setOnClickListener { onClick() }
        }

    private fun weight() = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
