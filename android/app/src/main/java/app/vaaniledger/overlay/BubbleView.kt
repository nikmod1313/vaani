package app.vaaniledger.overlay

import app.vaaniledger.ui.Theme

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/**
 * The floating bubble itself.
 *
 * A TextView rather than a FrameLayout wrapping one. The first version
 * overrode onMeasure with a bare setMeasuredDimension, which skipped
 * measuring the child entirely -- the circle drew fine and the "V" inside it
 * never appeared. Subclassing the thing that draws the text removes the whole
 * class of problem.
 *
 * Plain Views on purpose: Compose inside a WindowManager overlay needs
 * ViewTreeLifecycleOwner and SavedStateRegistryOwner wired by hand, and none
 * of that buys anything for a 54dp circle with a letter in it.
 */
class BubbleView(context: Context) : TextView(context) {

    private val sizePx = dp(54)

    init {
        text = "V"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false

        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(INDIGO)
            // A light ring keeps it legible over WhatsApp's dark chat
            // wallpaper as well as the light one.
            setStroke(dp(3), Color.argb(150, 255, 255, 255))
        }
        elevation = dp(8).toFloat()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Fixed circle. Measured directly because the view has no children.
        setMeasuredDimension(sizePx, sizePx)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        val INDIGO = Theme.primary
    }
}
