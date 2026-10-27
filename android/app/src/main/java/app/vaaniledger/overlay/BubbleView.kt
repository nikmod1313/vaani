package app.vaaniledger.overlay

import app.vaaniledger.ui.Theme
import app.vaaniledger.ui.Type

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/**
 * The floating bubble itself: an indigo seal with a gold rim and वा in it.
 *
 * A TextView rather than a FrameLayout wrapping one. The first version
 * overrode onMeasure with a bare setMeasuredDimension, which skipped
 * measuring the child entirely -- the circle drew fine and the letter inside
 * it never appeared. Subclassing the thing that draws the text removes the
 * whole class of problem.
 *
 * Plain Views on purpose: Compose inside a WindowManager overlay needs
 * ViewTreeLifecycleOwner and SavedStateRegistryOwner wired by hand, and none
 * of that buys anything for a 56dp circle with a letter in it.
 */
class BubbleView(context: Context) : TextView(context) {

    private val sizePx = dp(56)

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Theme.gold
    }
    private val outerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // A pale outer ring keeps it legible over WhatsApp's dark chat
        // wallpaper as well as the light one.
        color = Color.argb(170, 246, 238, 218)
    }

    init {
        text = "वा"
        setTextColor(Theme.goldPale)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Type.display(context)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(0, dp(3), 0, 0)
        elevation = dp(8).toFloat()
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Fixed circle. Measured directly because the view has no children.
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        disc.shader = RadialGradient(
            w * 0.38f, h * 0.32f, w * 0.75f,
            Color.rgb(0x2A, 0x3D, 0x7A), Theme.primaryDeep, Shader.TileMode.CLAMP,
        )
        rim.strokeWidth = dp(1).toFloat() * 1.4f
        outerRing.strokeWidth = dp(2).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        val r = width / 2f
        canvas.drawCircle(c, c, r - outerRing.strokeWidth / 2, outerRing)
        canvas.drawCircle(c, c, r - outerRing.strokeWidth, disc)
        canvas.drawCircle(c, c, r - dp(6), rim)
        super.onDraw(canvas)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        val INDIGO = Theme.primary
    }
}
