package app.vaaniledger.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

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
    private val onDismiss: () -> Unit,
    private val onCapture: () -> Unit = {},
) : LinearLayout(context) {

    private val ink = Color.rgb(0x12, 0x15, 0x1A)
    private val muted = Color.rgb(0x8B, 0x91, 0xA0)
    private val saffron = Color.rgb(0xC9, 0x75, 0x2F)
    private val good = Color.rgb(0x1F, 0x7A, 0x52)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(
                dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(),
                0f, 0f, 0f, 0f,
            )
        }
        elevation = dp(16).toFloat()

        addView(header())
        addView(stats())
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

        addView(TextView(context).apply {
            text = "VaaniLedger"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(context).apply {
            text = "✕"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { onDismiss() }
        })
    }

    private fun stats(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, dp(14), 0, dp(6))
        addView(stat("SALE", "₹4,240", ink), equalWeight())
        addView(stat("CASH", "₹3,100", good), equalWeight())
        addView(stat("UDHAAR", "₹1,140", saffron), equalWeight())
    }

    private fun stat(label: String, value: String, colour: Int): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = label
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                letterSpacing = 0.12f
            })
            addView(TextView(context).apply {
                text = value
                setTextColor(colour)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = Typeface.DEFAULT_BOLD
            })
        }

    private fun actions(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, dp(12), 0, 0)

        addView(button("Yeh likh lo", "read this chat", primary = true) { onCapture() })

        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            addView(button("Bol ke likho", "voice") {}, equalWeight())
            addView(View(context), LayoutParams(dp(8), 1))
            addView(button("Khata", "all customers") {}, equalWeight())
        })
    }

    private fun button(
        title: String,
        subtitle: String,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = GradientDrawable().apply {
            setColor(if (primary) BubbleView.INDIGO else Color.rgb(0xF4, 0xF5, 0xF7))
            cornerRadius = dp(11).toFloat()
        }
        isClickable = true
        setOnClickListener { onClick() }

        addView(TextView(context).apply {
            text = title
            setTextColor(if (primary) Color.WHITE else ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(context).apply {
            text = subtitle
            setTextColor(if (primary) Color.argb(180, 255, 255, 255) else muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        })
    }

    private fun equalWeight() = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
