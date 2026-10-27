package app.vaaniledger.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import app.vaaniledger.R

/**
 * The ornaments: everything that makes a plain View look like it belongs in
 * a bound register rather than a settings app.
 *
 * All of it is Canvas paths over a handful of bitmaps -- no vector XML, no
 * image per state. A rule that is drawn stays a hairline at every density,
 * and a bitmap border would not.
 */

private fun Context.px(v: Float) = v * resources.displayMetrics.density

/** Decoded art, held once per process. The pages are small; the art is reused on every one. */
internal object Art {
    private val cache = HashMap<Int, Bitmap>()

    fun get(context: Context, id: Int): Bitmap = synchronized(cache) {
        cache.getOrPut(id) {
            BitmapFactory.decodeResource(context.applicationContext.resources, id)
        }
    }
}

/**
 * Draw [bitmap] to cover [dst] completely, cropping what overflows.
 *
 * @param focusY which part of the image survives a vertical crop: 0 keeps
 *   the top, 1 keeps the bottom. The palace sits low in its frame, so the
 *   hero asks for the bottom and the splash for the middle.
 */
internal fun Canvas.drawCover(bitmap: Bitmap, dst: RectF, paint: Paint, focusX: Float = 0.5f, focusY: Float = 0.5f) {
    val scale = maxOf(dst.width() / bitmap.width, dst.height() / bitmap.height)
    val w = bitmap.width * scale
    val h = bitmap.height * scale
    val m = Matrix().apply {
        setScale(scale, scale)
        postTranslate(dst.left - (w - dst.width()) * focusX, dst.top - (h - dst.height()) * focusY)
    }
    save()
    clipRect(dst)
    drawBitmap(bitmap, m, paint)
    restore()
}

/**
 * A Mughal arch: straight jambs, then two curves meeting in a point at the
 * top. The jharokha window, the haveli door -- the shape that frames things
 * in the buildings this palette comes from.
 *
 * @param shoulder where the jambs stop and the curve starts, as a fraction
 *   of the height from the top.
 */
fun archPath(r: RectF, shoulder: Float = 0.4f): Path = Path().apply {
    val cx = r.centerX()
    val sy = r.top + r.height() * shoulder
    val w = r.width()
    moveTo(r.left, r.bottom)
    lineTo(r.left, sy)
    // Each half is an ogee in miniature: bowing out, then drawing in to a
    // point, which is what separates it from a plain Roman semicircle.
    cubicTo(r.left, sy - (sy - r.top) * 0.55f, cx - w * 0.30f, r.top + (sy - r.top) * 0.18f, cx, r.top)
    cubicTo(cx + w * 0.30f, r.top + (sy - r.top) * 0.18f, r.right, sy - (sy - r.top) * 0.55f, r.right, sy)
    lineTo(r.right, r.bottom)
    close()
}


/**
 * A card's border: fresh paper, an outer rule, and a fainter gold hairline
 * inset inside it -- the double rule a scribe draws round a ledger page.
 */
class ManuscriptFrame(
    private val context: Context,
    private val fill: Int = Theme.surface,
    private val outer: Int = Theme.border,
    private val radius: Float = context.px(6f),
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.px(1f)
        color = outer
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.px(0.7f)
        color = Color.argb(0x5A, Color.red(Theme.accent), Color.green(Theme.accent), Color.blue(Theme.accent))
    }
    /** A faint lift under the card, drawn rather than elevated so it stays warm, not grey. */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x16, 0x5A, 0x3C, 0x14) }

    override fun draw(canvas: Canvas) {
        val b = RectF(bounds)
        val lift = context.px(1.5f)
        canvas.drawRoundRect(RectF(b.left, b.top + lift, b.right, b.bottom), radius, radius, shadowPaint)
        val r = RectF(b.left, b.top, b.right, b.bottom - lift)
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        val half = outerPaint.strokeWidth / 2
        canvas.drawRoundRect(RectF(r.left + half, r.top + half, r.right - half, r.bottom - half), radius, radius, outerPaint)
        val inset = context.px(4f)
        canvas.drawRoundRect(
            RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset),
            (radius - inset / 2).coerceAtLeast(0f), (radius - inset / 2).coerceAtLeast(0f), innerPaint,
        )
    }

    override fun getPadding(padding: Rect): Boolean = false
    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The primary button: indigo, with a gold hairline inset like the rim of a seal. */
class SealFrame(
    private val context: Context,
    fill: Int = Theme.primary,
    rim: Int = Theme.gold,
    private val radius: Float = context.px(12f),
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.px(0.8f)
        color = Color.argb(0x8C, Color.red(rim), Color.green(rim), Color.blue(rim))
    }

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds)
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        val inset = context.px(3.5f)
        canvas.drawRoundRect(
            RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset),
            radius - inset, radius - inset, rimPaint,
        )
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** An arch, filled and outlined. The selected-tab marker and the category tiles. */
class ArchDrawable(
    private val context: Context,
    private val fill: Int,
    private val stroke: Int? = null,
    private val shoulder: Float = 0.45f,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.px(1f)
        color = stroke ?: Color.TRANSPARENT
    }

    override fun draw(canvas: Canvas) {
        val half = strokePaint.strokeWidth / 2
        val r = RectF(bounds).apply { inset(half, half) }
        val p = archPath(r, shoulder)
        canvas.drawPath(p, fillPaint)
        if (stroke != null) canvas.drawPath(p, strokePaint)
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The parchment every page is written on: the scanned paper, cropped to
 * cover, with its edges browned a little as old paper does. Drawn behind the
 * scroll view, so the texture holds still while the writing moves.
 */
class PaperBackground(
    private val context: Context,
    /**
     * A paisley endpaper pressed into the parchment, as the inside cover of
     * a bound register is printed. On for the dashboard tabs, which a
     * shopkeeper lives in; off for onboarding, review and the sheets over
     * WhatsApp, where the page should be as quiet as possible behind text.
     */
    private val pattern: Boolean = false,
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    /** Gold line work at low strength: present in the page, never competing with the writing on it. */
    private val patternPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = 0x5A }
    private val vignette = Paint(Paint.ANTI_ALIAS_FLAG)
    /** The scan is warm and uneven; a parchment wash over it keeps contrast predictable for text. */
    private val wash = Paint().apply { color = Color.argb(0x5C, 0xF6, 0xEE, 0xD9) }

    override fun onBoundsChange(bounds: Rect) {
        val r = maxOf(bounds.width(), bounds.height()) * 0.75f
        vignette.shader = RadialGradient(
            bounds.exactCenterX(), bounds.exactCenterY() * 0.9f, r,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(0x38, 0x7A, 0x52, 0x1E)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds)
        canvas.drawColor(Theme.paper)
        canvas.drawCover(Art.get(context, R.drawable.tex_parchment), r, paint)
        if (pattern) canvas.drawCover(Art.get(context, R.drawable.tex_paisley), r, patternPaint, focusY = 0f)
        canvas.drawRect(r, wash)
        canvas.drawRect(r, vignette)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** The navigation bar: deep indigo under a gold double rule. */
class NavBarBackground(private val context: Context) : Drawable() {
    private val fill = Paint().apply { color = Theme.primaryDeep }
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.px(1f) }
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.px(0.6f) }

    override fun onBoundsChange(bounds: Rect) {
        val g = Theme.gold
        val clear = Color.argb(0, Color.red(g), Color.green(g), Color.blue(g))
        rule.shader = LinearGradient(
            bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f,
            intArrayOf(clear, g, g, clear), floatArrayOf(0f, 0.25f, 0.75f, 1f), Shader.TileMode.CLAMP,
        )
        faint.shader = LinearGradient(
            bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f,
            intArrayOf(clear, Color.argb(0x66, Color.red(g), Color.green(g), Color.blue(g)), clear),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, fill)
        val y1 = b.top + context.px(1f)
        canvas.drawLine(b.left.toFloat(), y1, b.right.toFloat(), y1, rule)
        val y2 = b.top + context.px(4f)
        canvas.drawLine(b.left.toFloat(), y2, b.right.toFloat(), y2, faint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}


/**
 * A gold hairline divider.
 *
 * With a flourish it is the manuscript divider -- line, lozenge, line, each
 * line fading to nothing at its far end. Without, a single line fading out
 * to the right, used after a section's eyebrow.
 */
@SuppressLint("ViewConstructor")
class OrnamentRule(
    context: Context,
    private val flourish: Boolean = true,
    private val colour: Int = Theme.accent,
) : View(context) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.px(0.9f) }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.px(0.9f)
        color = colour
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cy = height / 2f
        val clear = Color.argb(0, Color.red(colour), Color.green(colour), Color.blue(colour))
        if (!flourish) {
            line.shader = LinearGradient(0f, 0f, w, 0f, colour.let {
                Color.argb(0x99, Color.red(it), Color.green(it), Color.blue(it))
            }, clear, Shader.TileMode.CLAMP)
            canvas.drawLine(0f, cy, w, cy, line)
            return
        }
        val cx = w / 2f
        val d = context.px(4f)
        val gap = context.px(10f)
        line.shader = LinearGradient(0f, 0f, cx - gap, 0f, clear, colour, Shader.TileMode.CLAMP)
        canvas.drawLine(0f, cy, cx - gap, cy, line)
        line.shader = LinearGradient(cx + gap, 0f, w, 0f, colour, clear, Shader.TileMode.CLAMP)
        canvas.drawLine(cx + gap, cy, w, cy, line)

        // The lozenge, with a dot either side.
        val p = Path().apply {
            moveTo(cx, cy - d); lineTo(cx + d, cy); lineTo(cx, cy + d); lineTo(cx - d, cy); close()
        }
        canvas.drawPath(p, ring)
        canvas.drawCircle(cx, cy, context.px(1.2f), mark)
        canvas.drawCircle(cx - gap + context.px(3f), cy, context.px(1.1f), mark)
        canvas.drawCircle(cx + gap - context.px(3f), cy, context.px(1.1f), mark)
    }
}


/**
 * A picture seen through an arch, outlined in gold. Children draw on top of
 * the picture, so a heading can sit inside the window.
 *
 * @param arched false gives the same picture in a plain rounded panel, for
 *   the hero, where an arch would waste the width a phone can least spare.
 */
class ArchWindow(
    context: Context,
    private val art: Int,
    private val arched: Boolean = true,
    private val focusY: Float = 1f,
    /** How much of an indigo wash to lay over the picture, for text legibility. */
    private val shade: Float = 0f,
) : FrameLayout(context) {

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint()
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.px(1.4f)
        color = Theme.gold
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.px(0.7f)
        color = Color.argb(0x99, Color.red(Theme.goldPale), Color.green(Theme.goldPale), Color.blue(Theme.goldPale))
    }

    init {
        setWillNotDraw(false)
    }

    private fun shape(r: RectF): Path =
        if (arched) archPath(r) else Path().apply {
            addRoundRect(r, context.px(8f), context.px(8f), Path.Direction.CW)
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shadePaint.shader = LinearGradient(
            0f, 0f, w * 0.7f, h.toFloat(),
            Color.argb((shade * 255).toInt(), Color.red(Theme.primaryDeep), Color.green(Theme.primaryDeep), Color.blue(Theme.primaryDeep)),
            Color.argb((shade * 90).toInt(), Color.red(Theme.primaryDeep), Color.green(Theme.primaryDeep), Color.blue(Theme.primaryDeep)),
            Shader.TileMode.CLAMP,
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val clip = shape(r)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawColor(Theme.primary)
        canvas.drawCover(Art.get(context, art), r, imagePaint, focusY = focusY)
        if (shade > 0f) canvas.drawRect(r, shadePaint)
        super.dispatchDraw(canvas)
        canvas.restore()

        val o = outer.strokeWidth / 2
        canvas.drawPath(shape(RectF(r.left + o, r.top + o, r.right - o, r.bottom - o)), outer)
        val i = context.px(5f)
        canvas.drawPath(shape(RectF(r.left + i, r.top + i, r.right - i, r.bottom - i)), inner)
    }
}

/** A cut-out illustration, drawn to fit its box without cropping. */
class ArtView(context: Context, private val art: Int, private val alignBottom: Boolean = true) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val bmp = Art.get(context, art)
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = if (alignBottom) height - h else (height - h) / 2f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), paint)
    }
}

/**
 * The bottom sheets that rise over WhatsApp: parchment, rounded at the top
 * only, with a gold rim along the top edge so the sheet reads as a page
 * laid over the chat rather than a grey panel.
 */
class SheetBackground(private val context: Context) : Drawable() {
    private val paper = PaperBackground(context)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.px(1.2f)
        color = Theme.accent
    }
    private val radius = context.px(24f)

    private fun shape(r: RectF) = Path().apply {
        addRoundRect(r, floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f), Path.Direction.CW)
    }

    override fun onBoundsChange(bounds: Rect) { paper.bounds = bounds }

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds)
        canvas.save()
        canvas.clipPath(shape(r))
        paper.draw(canvas)
        canvas.restore()
        val h = rim.strokeWidth / 2
        val top = Path().apply {
            moveTo(r.left + h, r.bottom)
            lineTo(r.left + h, r.top + radius)
            quadTo(r.left + h, r.top + h, r.left + radius, r.top + h)
            lineTo(r.right - radius, r.top + h)
            quadTo(r.right - h, r.top + h, r.right - h, r.top + radius)
            lineTo(r.right - h, r.bottom)
        }
        canvas.drawPath(top, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
