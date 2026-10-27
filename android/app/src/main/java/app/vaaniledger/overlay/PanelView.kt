package app.vaaniledger.overlay

import app.vaaniledger.ui.LineIcon
import app.vaaniledger.ui.ManuscriptFrame
import app.vaaniledger.ui.OrnamentRule
import app.vaaniledger.ui.SealFrame
import app.vaaniledger.ui.SheetBackground
import app.vaaniledger.ui.Theme
import app.vaaniledger.ui.Type
import app.vaaniledger.ui.Views.dp
import app.vaaniledger.ui.Views.eyebrow
import app.vaaniledger.ui.Views.pressable
import app.vaaniledger.ui.Views.text

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import app.vaaniledger.data.Strings

/**
 * The mini dashboard the bubble expands into.
 *
 * Today's three numbers and the actions, drawn over whatever app is in front.
 * The numbers come from Room, never the network -- this has to open instantly
 * on a shop counter with no signal, which is where it will most often open.
 */
@SuppressLint("ViewConstructor")
class PanelView(
    context: Context,
    private val today: Totals,
    private val strings: Strings.Panel,
    private val onDismiss: () -> Unit,
    private val onCapture: () -> Unit = {},
    private val onVoice: () -> Unit = {},
    private val onKhata: () -> Unit = {},
) : LinearLayout(context) {

    /** Today's figures, read from Room. Nothing here is a placeholder. */
    data class Totals(
        val sale: Double = 0.0,
        val cashIn: Double = 0.0,
        val udhaar: Double = 0.0,
        val entries: Int = 0,
        val pendingSync: Int = 0,
    )

    private val sheets = Strings.of(app.vaaniledger.data.ShopSettings.language(context)).sheets
    private val ink = Theme.ink
    private val muted = Theme.muted

    init {
        orientation = VERTICAL
        setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(34))
        background = SheetBackground(context)
        elevation = context.dp(16).toFloat()

        addView(header())
        addView(stats())
        addView(subtitle())
        addView(actions())

        // Tapping outside collapses back to the bubble.
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                onDismiss()
                true
            } else {
                false
            }
        }

    }

    // ----------------------------------------------------------------------

    private fun header(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(context.text("वाणी", 22f, Theme.primary, Type.display(context)))
        addView(View(context), LayoutParams(0, 1, 1f))

        addView(View(context).apply {
            background = LineIcon(context, LineIcon.Glyph.CLOSE, muted, sizeDp = 20)
            pressable()
            setOnClickListener { onDismiss() }
        }, LayoutParams(context.dp(20), context.dp(20)))
    }

    private fun rupees(v: Double) = "₹%,.0f".format(v)

    /**
     * The honest line under the numbers: how many entries today, and how many
     * have not reached the server yet.
     *
     * The pending count is the visible offline queue — it is the difference
     * between "sync is broken" and "sync has not run yet", and the shopkeeper
     * should be able to see which.
     */
    private fun subtitle(): View = context.text(
        when {
            today.entries == 0 -> strings.noEntriesToday
            today.pendingSync > 0 -> sheets.entriesPending(today.entries, today.pendingSync)
            else -> sheets.entriesSynced(today.entries)
        },
        12.5f, if (today.pendingSync > 0) Theme.warn else muted, Type.displayItalic(context),
    ).apply { setPadding(0, context.dp(2), 0, 0) }

    private fun stats(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, context.dp(14), 0, context.dp(6))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(stat(strings.statLabelSale, rupees(today.sale), ink), equalWeight())
            addView(stat(strings.statLabelCash, rupees(today.cashIn), Theme.good), equalWeight())
            addView(stat(strings.statLabelCredit, rupees(today.udhaar), Theme.warn), equalWeight())
        })
        addView(OrnamentRule(context), LayoutParams(LayoutParams.MATCH_PARENT, context.dp(12)).apply {
            topMargin = context.dp(12)
        })
    }

    private fun stat(label: String, value: String, colour: Int): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            addView(context.eyebrow(label, muted).apply { textSize = 10f })
            addView(context.text(value, 22f, colour, Type.display(context)))
        }

    private fun actions(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, context.dp(12), 0, 0)

        addView(button(LineIcon.Glyph.CHAT, strings.readChat, strings.readChatSub, primary = true) { onCapture() })

        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, context.dp(10), 0, 0)
            addView(button(LineIcon.Glyph.MIC, strings.speak, strings.speakSub) { onVoice() }, equalWeight())
            addView(View(context), LayoutParams(context.dp(10), 1))
            addView(button(LineIcon.Glyph.BOOK, strings.ledger, strings.ledgerSub) { onKhata() }, equalWeight())
        })
    }

    private fun button(
        icon: LineIcon.Glyph,
        title: String,
        subtitle: String,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(16), context.dp(13), context.dp(14), context.dp(13))
        background = if (primary) SealFrame(context) else ManuscriptFrame(context)
        pressable()
        setOnClickListener { onClick() }

        addView(View(context).apply {
            background = LineIcon(context, icon, if (primary) Theme.gold else Theme.primary, sizeDp = 22)
        }, LayoutParams(context.dp(22), context.dp(22)).apply { marginEnd = context.dp(12) })

        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(context.text(title, 15.5f, if (primary) Theme.onPrimary else ink, Type.medium(context)))
            addView(context.text(subtitle, 11.5f, if (primary) Theme.onPrimaryMuted else muted))
        })
    }

    private fun equalWeight() = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
}
