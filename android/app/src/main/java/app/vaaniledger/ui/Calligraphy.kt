package app.vaaniledger.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * वाणी, written out in gold line.
 *
 * The opening of the app is the name being written, the way a scribe opens
 * a new bahi: first the arch of the page's frame is ruled, then each stroke
 * of the word is traced with the nib visible at its tip, and only once the
 * outline is complete does the ink flood in. A light then passes across the
 * gilt, and the page is ready.
 *
 * The letterforms are the real glyphs of Tiro Devanagari Hindi, turned into
 * outlines with Paint.getTextPath -- so the shirorekha, the matras and the
 * conjunct shapes are a type designer's, not an approximation drawn here.
 *
 * Timeline, as fractions of [DURATION]:
 *   0.00-0.40  the arch is ruled
 *   0.12-0.72  the word is traced, contour by contour
 *   0.66-0.86  the ink fills
 *   0.80-1.00  the light sweeps across
 */
@SuppressLint("ViewConstructor")
class ScriptLineArtView(
    context: Context,
    private val word: String = "वाणी",
) : View(context) {

    private val density = resources.displayMetrics.density

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Type.display(context)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.6f * density
        color = Theme.goldPale
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 6f * density
        color = Color.argb(0x2A, Color.red(Theme.gold), Color.green(Theme.gold), Color.blue(Theme.gold))
    }
    private val archPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.1f * density
        color = Theme.gold
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nib = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val nibHalo = Paint(Paint.ANTI_ALIAS_FLAG)

    private val textPath = Path()
    private val archPathOuter = Path()
    private val archPathInner = Path()
    private val textBounds = RectF()

    private class Contour(val measure: PathMeasure, val length: Float)

    private val textContours = mutableListOf<Contour>()
    private val archContours = mutableListOf<Contour>()
    private var textLength = 0f
    private var archLength = 0f

    private var progress = 0f
    private var animator: ValueAnimator? = null

    /** Called once the light has crossed the word. */
    var onFinished: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        build(w.toFloat(), h.toFloat())
    }

    private fun build(w: Float, h: Float) {
        // Size the word to 68% of the width, then centre it a little below
        // the middle of the arch, where the eye expects the subject.
        textPaint.textSize = 100f
        val probe = Path()
        textPaint.getTextPath(word, 0, word.length, 0f, 0f, probe)
        val pb = RectF().also { probe.computeBounds(it, true) }
        val scale = (w * 0.68f) / pb.width()
        textPaint.textSize = 100f * scale

        textPath.reset()
        textPaint.getTextPath(word, 0, word.length, 0f, 0f, textPath)
        textPath.computeBounds(textBounds, true)
        val dx = (w - textBounds.width()) / 2f - textBounds.left
        val dy = h * 0.60f - textBounds.centerY()
        textPath.transform(Matrix().apply { setTranslate(dx, dy) })
        textPath.computeBounds(textBounds, true)

        // The frame: a jharokha arch with a second, finer arch inside it.
        val frame = RectF(w * 0.06f, h * 0.04f, w * 0.94f, h * 0.98f)
        archPathOuter.set(archPath(frame, shoulder = 0.42f))
        val i = 7f * density
        archPathInner.set(archPath(RectF(frame.left + i, frame.top + i * 1.6f, frame.right - i, frame.bottom - i), 0.42f))

        textContours.clear(); textLength = 0f
        collect(textPath, textContours).also { textLength = it }
        archContours.clear(); archLength = 0f
        collect(archPathOuter, archContours).also { archLength = it }
        archLength += collect(archPathInner, archContours)

        fill.shader = LinearGradient(
            0f, textBounds.top, 0f, textBounds.bottom,
            intArrayOf(Theme.goldPale, Theme.gold, Color.rgb(0xB0, 0x84, 0x36)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        nibHalo.shader = null
        nibHalo.color = Color.argb(0x66, Color.red(Theme.goldPale), Color.green(Theme.goldPale), Color.blue(Theme.goldPale))
    }

    private fun collect(path: Path, into: MutableList<Contour>): Float {
        val m = PathMeasure(path, false)
        var total = 0f
        do {
            val len = m.length
            if (len > 0.5f) {
                // A fresh PathMeasure per contour: one measure can only walk
                // forward, and each frame needs to re-read every contour.
                val copy = Path()
                m.getSegment(0f, len, copy, true)
                into.add(Contour(PathMeasure(copy, false), len))
                total += len
            }
        } while (m.nextContour())
        return total
    }

    fun play(duration: Long = DURATION) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onFinished?.invoke()
                }
            })
            start()
        }
    }

    /** Jump to the finished word -- a tap on the splash skips the writing, not the name. */
    fun finishNow() {
        animator?.cancel()
        progress = 1f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun window(p: Float, start: Float, end: Float) =
        ((p - start) / (end - start)).coerceIn(0f, 1f)

    /** Ease in-out: a pen slows into the start and end of a stroke. */
    private fun ease(t: Float) = (t * t * (3 - 2 * t))

    override fun onDraw(canvas: Canvas) {
        if (textContours.isEmpty()) return

        // 1. The arch
        val a = ease(window(progress, 0f, 0.40f))
        if (a > 0f) {
            archPaint.alpha = (255 * (0.35f + 0.65f * a)).toInt()
            drawTraced(canvas, archContours, archLength * a, archPaint, null)
        }

        // 2. The word, traced
        val t = window(progress, 0.12f, 0.72f)
        val f = ease(window(progress, 0.66f, 0.86f))
        if (t > 0f) {
            // Outline fades as the ink fills, so the finished word is solid gilt
            // with a fine rim, not a filled shape with a second line round it.
            stroke.alpha = (255 * (1f - 0.55f * f)).toInt()
            glow.alpha = (0x2A * (1f - f)).toInt()
            val tip = drawTraced(canvas, textContours, textLength * ease(t), stroke, glow)
            if (tip != null && t < 1f) {
                canvas.drawCircle(tip[0], tip[1], 6f * density, nibHalo)
                canvas.drawCircle(tip[0], tip[1], 2.2f * density, nib)
            }
        }

        // 3. The ink
        if (f > 0f) {
            fill.alpha = (255 * f).toInt()
            canvas.drawPath(textPath, fill)
        }

        // 4. The light
        val s = window(progress, 0.80f, 1f)
        if (s > 0f && s < 1f) {
            val band = textBounds.width() * 0.22f
            val x = textBounds.left - band + (textBounds.width() + band * 2) * s
            sheen.shader = LinearGradient(
                x - band, textBounds.top, x + band, textBounds.bottom,
                intArrayOf(Color.TRANSPARENT, Color.argb(0xB0, 255, 250, 235), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.save()
            canvas.clipPath(textPath)
            canvas.drawRect(textBounds, sheen)
            canvas.restore()
        }
    }

    /**
     * Draw contours in order until [budget] length is spent, the last one
     * partially. Returns where the pen is, for the nib.
     */
    private fun drawTraced(
        canvas: Canvas,
        contours: List<Contour>,
        budget: Float,
        paint: Paint,
        under: Paint?,
    ): FloatArray? {
        var left = budget
        val seg = Path()
        var tip: FloatArray? = null
        for (c in contours) {
            if (left <= 0f) break
            val take = minOf(c.length, left)
            seg.reset()
            c.measure.getSegment(0f, take, seg, true)
            if (under != null) canvas.drawPath(seg, under)
            canvas.drawPath(seg, paint)
            if (take < c.length) {
                tip = FloatArray(2).also { c.measure.getPosTan(take, it, null) }
            }
            left -= take
        }
        return tip
    }

    companion object {
        const val DURATION = 3000L
    }
}
