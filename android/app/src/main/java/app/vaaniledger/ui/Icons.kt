package app.vaaniledger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The app's icons, drawn as single-weight line art.
 *
 * These replace the emoji the screens used to carry. Emoji render
 * differently on every phone -- a different artist, a different size, a
 * different idea of what a "ledger" looks like -- and they set the tone of a
 * chat app inside what should feel like a careful book of accounts. A line
 * at one weight, in the app's own ink, reads the same everywhere.
 *
 * Each glyph is a path on a 24-unit square in a small SVG-like grammar:
 * M L H V Q C Z as in SVG (absolute only), plus `O cx cy r` for a circle and
 * `R l t r b radius` for a rounded rectangle.
 */
class LineIcon(
    private val context: Context,
    private val glyph: Glyph,
    colour: Int,
    private val sizeDp: Int = 22,
    strokeUnits: Float = 1.6f,
) : Drawable() {

    enum class Glyph(val spec: String) {
        // navigation
        HOME("M5 21 V10.5 C5 7 8 4.6 12 3 C16 4.6 19 7 19 10.5 V21 M3 21 H21 M9.6 21 V15 C9.6 13.4 10.6 12.3 12 11.8 C13.4 12.3 14.4 13.4 14.4 15 V21"),
        REPORT("M3 21 H21 R5 13 8.4 21 0.8 R10.3 7.5 13.7 21 0.8 R15.6 10.5 19 21 0.8"),
        LEDGER("R5 3 19 21 1.6 M8.4 3 V21 M11.5 8 H16 M11.5 11.5 H16 M11.5 15 H14.2"),
        SETTINGS("O12 12 3.6 M12 2.5 V5 M12 19 V21.5 M2.5 12 H5 M19 12 H21.5 M5.3 5.3 L7.1 7.1 M16.9 16.9 L18.7 18.7 M5.3 18.7 L7.1 16.9 M16.9 7.1 L18.7 5.3"),

        // actions
        CHAT("M4 5.5 C4 4.6 4.6 4 5.5 4 H18.5 C19.4 4 20 4.6 20 5.5 V15 C20 15.9 19.4 16.5 18.5 16.5 H11 L6.5 20.5 V16.5 H5.5 C4.6 16.5 4 15.9 4 15 Z M8 8.6 H16 M8 12 H13.5"),
        FILE("M6 3 H14 L19 8 V21 H6 Z M14 3 V8 H19 M12.5 11 V17.2 M10 14.8 L12.5 17.3 L15 14.8"),
        MIC("R9 3 15 14 3 M6 11 C6 15 8.7 17.5 12 17.5 C15.3 17.5 18 15 18 11 M12 17.5 V21 M9 21 H15"),
        BOOK("M3 5.5 C6.5 4.5 9.5 5 12 7 C14.5 5 17.5 4.5 21 5.5 V19 C17.5 18 14.5 18.5 12 20.5 C9.5 18.5 6.5 18 3 19 Z M12 7 V20.5"),
        BUBBLE("O12 12 8.5 M9.2 9 L12 15.4 L14.8 9"),
        LOCK("R5 11 19 21 2 M8 11 V8 C8 5.4 9.8 4 12 4 C14.2 4 16 5.4 16 8 V11 M12 15 V17"),
        CHECK("M5 12.5 L10 17 L19 7"),
        CHEVRON("M9 5 L16 12 L9 19"),
        BACK("M15 5 L8 12 L15 19"),
        CLOSE("M6 6 L18 18 M18 6 L6 18"),
        PLAY("M8 5 L19 12 L8 19 Z"),
        STOP("R6 6 18 18 2"),
        BELL("M6 17 V11 C6 7.4 8.6 5 12 5 C15.4 5 18 7.4 18 11 V17 Z M4 17 H20 M10 20 C10.7 21.2 13.3 21.2 14 20 M12 3 V5"),
        PENCIL("M4 20 L5 15 L15 5 L19 9 L9 19 Z M13 7 L17 11"),
        GLOBE("O12 12 9 M3 12 H21 M12 3 C9 6 9 18 12 21 C15 18 15 6 12 3"),
        SERVER("R4 4 20 10.5 1.5 R4 13.5 20 20 1.5 M7.5 7.25 H8.5 M7.5 16.75 H8.5"),
        SEARCH("O10.5 10.5 6.5 M15.3 15.3 L20 20"),
        QR("R4 4 10 10 1 R14 4 20 10 1 R4 14 10 20 1 M14 14 H16.5 V16.5 H14 Z M19 14 H20 M14 19.5 V20 M17.5 17.5 H20 V20 H17.5 Z"),
        SHARE("M12 15 V4 M8 8 L12 4 L16 8 M5 13 V19 C5 19.6 5.4 20 6 20 H18 C18.6 20 19 19.6 19 19 V13"),
        SPARK("M12 3 L13.6 10.4 L21 12 L13.6 13.6 L12 21 L10.4 13.6 L3 12 L10.4 10.4 Z"),

        // shop categories
        SHOP("M3 9 L5 4 H19 L21 9 C21 10.7 19.7 11.5 18.6 11.5 C17.4 11.5 16.3 10.7 16.3 9 C16.3 10.7 15.2 11.5 14.1 11.5 C13 11.5 12 10.7 12 9 C12 10.7 11 11.5 9.9 11.5 C8.8 11.5 7.7 10.7 7.7 9 C7.7 10.7 6.6 11.5 5.4 11.5 C4.3 11.5 3 10.7 3 9 Z M5 11.5 V20 H19 V11.5 M10 20 V15 H14 V20"),
        SACK("M8.5 6.5 C10.5 7.6 13.5 7.6 15.5 6.5 L17.6 10 C20 14.5 19 20.5 12 20.5 C5 20.5 4 14.5 6.4 10 Z M8.5 6.5 C9 4.5 10.3 3.5 12 3.5 C13.7 3.5 15 4.5 15.5 6.5"),
        KURTA("M8.5 3.5 L4 6.5 L5.5 10.5 L8 9.5 V20.5 H16 V9.5 L18.5 10.5 L20 6.5 L15.5 3.5 C14.5 5.5 9.5 5.5 8.5 3.5 Z M12 5 V11"),
        MEDICINE("R4 4 20 20 4 M12 8 V16 M8 12 H16"),
        HAMMER("M4.5 4.5 H14.5 L16.5 6.5 L14.5 8.5 H4.5 Z M10 8.5 V20.5 M8.6 20.5 H11.4"),
        PEN("M12 3 L18 10 L15 20.5 H9 L6 10 Z M12 3 V11.8 O12 13 1.2"),
        BULB("M9 16.5 C6.5 14.8 5.5 12.5 5.8 10 C6.3 6.3 8.9 4 12 4 C15.1 4 17.7 6.3 18.2 10 C18.5 12.5 17.5 14.8 15 16.5 Z M9.5 19.2 H14.5 M10.5 21.5 H13.5"),
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colour
    }
    private val unitStroke = strokeUnits
    private val path = parse(glyph)
    private val scaled = Path()

    override fun getIntrinsicWidth() = (sizeDp * context.resources.displayMetrics.density).toInt()
    override fun getIntrinsicHeight() = intrinsicWidth

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f)
        }
        path.transform(m, scaled)
        paint.strokeWidth = unitStroke * s
        canvas.drawPath(scaled, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private val cache = HashMap<Glyph, Path>()
        private val token = Regex("[A-Za-z]|-?\\d*\\.?\\d+")

        private fun parse(glyph: Glyph): Path = synchronized(cache) {
            cache.getOrPut(glyph) { build(glyph.spec) }
        }

        private fun build(spec: String): Path {
            val p = Path()
            val t = token.findAll(spec).map { it.value }.toList()
            var i = 0
            var x = 0f
            var y = 0f
            var cmd = 'M'
            fun n() = t[i++].toFloat()
            while (i < t.size) {
                if (t[i][0].isLetter()) cmd = t[i++][0]
                when (cmd) {
                    'M' -> { x = n(); y = n(); p.moveTo(x, y); cmd = 'L' }
                    'L' -> { x = n(); y = n(); p.lineTo(x, y) }
                    'H' -> { x = n(); p.lineTo(x, y) }
                    'V' -> { y = n(); p.lineTo(x, y) }
                    'Q' -> { val a = n(); val b = n(); x = n(); y = n(); p.quadTo(a, b, x, y) }
                    'C' -> {
                        val a = n(); val b = n(); val c = n(); val d = n(); x = n(); y = n()
                        p.cubicTo(a, b, c, d, x, y)
                    }
                    'Z' -> p.close()
                    'O' -> p.addCircle(n(), n(), n(), Path.Direction.CW)
                    'R' -> {
                        val r = RectF(n(), n(), n(), n()); val rad = n()
                        p.addRoundRect(r, rad, rad, Path.Direction.CW)
                    }
                    else -> i++
                }
            }
            return p
        }
    }
}
