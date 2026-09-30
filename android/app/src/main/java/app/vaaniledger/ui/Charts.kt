package app.vaaniledger.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Hand-drawn charts.
 *
 * No charting library, for the same reason the web dashboard hand-rolls its
 * SVG: a dependency that renders a bar is not worth the megabytes or the
 * version churn, and this app is meant to be small enough to sideload over a
 * shop's connection. Everything here is Canvas primitives.
 *
 * All of them accept an EMPTY list and draw a readable "no data yet" state
 * rather than dividing by zero or showing an axis with nothing on it. A new
 * shop opens this page before it has sold anything, and that first impression
 * should not be a crash.
 */

/**
 * Chart colours, aliased to [Theme] so the charts cannot drift from the rest
 * of the app -- which is exactly what happened when they each held their own
 * hex values.
 */
object ChartPalette {
    val ink = Theme.ink
    val muted = Theme.muted
    val grid = Theme.border
    val indigo = Theme.primary
    val good = Theme.good
    val warn = Theme.warn
    val saffron = Theme.accent
    val series = Theme.series
}


private fun Paint.text(size: Float, colour: Int, bold: Boolean = false) = apply {
    isAntiAlias = true
    textSize = size
    color = colour
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

/** Short money, because an axis has no room for "₹1,25,000". */
fun shortMoney(value: Double): String = when {
    value >= 10_000_000 -> "₹%.1fCr".format(value / 10_000_000)
    value >= 100_000 -> "₹%.1fL".format(value / 100_000)
    value >= 1_000 -> "₹%.0fk".format(value / 1_000)
    else -> "₹%.0f".format(value)
}


/**
 * Vertical bars over a labelled baseline. Monthly, quarterly, annual.
 *
 * @param values one bar each, already in display order (oldest first).
 */
@SuppressLint("ViewConstructor")
class BarChartView(
    context: Context,
    private var labels: List<String> = emptyList(),
    private var values: List<Double> = emptyList(),
    private val emptyText: String = "No data yet",
    private val barColour: Int = ChartPalette.indigo,
) : View(context) {

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColour }
    private val gridPaint = Paint().apply { color = ChartPalette.grid; strokeWidth = dp(1f) }
    private val labelPaint = Paint().text(sp(10f), ChartPalette.muted)
    private val valuePaint = Paint().text(sp(10f), ChartPalette.ink, bold = true)
    private val emptyPaint = Paint().text(sp(13f), ChartPalette.muted).apply {
        textAlign = Paint.Align.CENTER
    }

    fun setData(labels: List<String>, values: List<Double>) {
        this.labels = labels
        this.values = values
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(320f).toInt(), widthSpec),
            resolveSize(dp(200f).toInt(), heightSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        if (values.isEmpty() || values.all { it <= 0.0 }) {
            canvas.drawText(emptyText, w / 2f, h / 2f, emptyPaint)
            return
        }

        val bottom = h - dp(26f)      // room for the period label
        val top = dp(20f)             // room for the value on top of the tallest bar
        val max = values.max().coerceAtLeast(1.0)

        // Three gridlines. More is noise on a phone.
        for (i in 0..2) {
            val y = bottom - (bottom - top) * (i / 2f)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val slot = w / values.size
        val barWidth = (slot * 0.56f).coerceAtMost(dp(44f))

        values.forEachIndexed { i, value ->
            val centre = slot * i + slot / 2f
            val barHeight = ((value / max) * (bottom - top)).toFloat()
            val rect = RectF(
                centre - barWidth / 2f,
                bottom - barHeight.coerceAtLeast(dp(2f)),
                centre + barWidth / 2f,
                bottom,
            )
            canvas.drawRoundRect(rect, dp(3f), dp(3f), bar)

            if (value > 0) {
                valuePaint.textAlign = Paint.Align.CENTER
                canvas.drawText(shortMoney(value), centre, rect.top - dp(5f), valuePaint)
            }
            labels.getOrNull(i)?.let {
                labelPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(it, centre, h - dp(8f), labelPaint)
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}


/**
 * Horizontal bars with a name on the left and a figure on the right.
 *
 * The right shape for rankings -- top products, top customers -- because the
 * labels are words of varying length, which a vertical axis cannot fit.
 */
@SuppressLint("ViewConstructor")
class RankBarView(
    context: Context,
    private var rows: List<Row> = emptyList(),
    private val emptyText: String = "No data yet",
) : View(context) {

    data class Row(val label: String, val value: Double, val caption: String = "")

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ChartPalette.grid }
    private val namePaint = Paint().text(sp(13f), ChartPalette.ink, bold = true)
    private val captionPaint = Paint().text(sp(10f), ChartPalette.muted)
    private val valuePaint = Paint().text(sp(13f), ChartPalette.ink, bold = true).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val emptyPaint = Paint().text(sp(13f), ChartPalette.muted).apply {
        textAlign = Paint.Align.CENTER
    }

    fun setRows(rows: List<Row>) {
        this.rows = rows
        requestLayout()
        invalidate()
    }

    private fun rowHeight() = dp(46f)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val needed = if (rows.isEmpty()) dp(90f) else rowHeight() * rows.size + dp(8f)
        setMeasuredDimension(
            resolveSize(dp(320f).toInt(), widthSpec),
            resolveSize(needed.toInt(), heightSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (rows.isEmpty()) {
            canvas.drawText(emptyText, w / 2f, height / 2f, emptyPaint)
            return
        }

        val max = rows.maxOf { it.value }.coerceAtLeast(1.0)
        val barTrackWidth = w

        rows.forEachIndexed { i, row ->
            val top = rowHeight() * i + dp(4f)
            val barTop = top + dp(22f)
            val barBottom = barTop + dp(8f)

            canvas.drawText(row.label, 0f, top + dp(14f), namePaint)
            canvas.drawText(shortMoney(row.value), w, top + dp(14f), valuePaint)

            canvas.drawRoundRect(
                RectF(0f, barTop, barTrackWidth, barBottom), dp(4f), dp(4f), track,
            )
            bar.color = ChartPalette.series[i % ChartPalette.series.size]
            val filled = (barTrackWidth * (row.value / max)).toFloat().coerceAtLeast(dp(4f))
            canvas.drawRoundRect(RectF(0f, barTop, filled, barBottom), dp(4f), dp(4f), bar)

            if (row.caption.isNotEmpty()) {
                canvas.drawText(row.caption, 0f, barBottom + dp(12f), captionPaint)
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}


/**
 * A donut for composition -- how the money arrived.
 *
 * A donut rather than a pie so the total sits in the hole, which is the
 * number most people actually want, and a legend below because slice labels
 * never fit on a phone.
 */
@SuppressLint("ViewConstructor")
class DonutChartView(
    context: Context,
    private var slices: List<Slice> = emptyList(),
    private val centreCaption: String = "",
    private val emptyText: String = "No data yet",
) : View(context) {

    data class Slice(val label: String, val value: Double, val colour: Int)

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val totalPaint = Paint().text(sp(18f), ChartPalette.ink, bold = true).apply {
        textAlign = Paint.Align.CENTER
    }
    private val captionPaint = Paint().text(sp(10f), ChartPalette.muted).apply {
        textAlign = Paint.Align.CENTER
    }
    private val legendPaint = Paint().text(sp(12f), ChartPalette.ink)
    private val swatch = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emptyPaint = Paint().text(sp(13f), ChartPalette.muted).apply {
        textAlign = Paint.Align.CENTER
    }

    fun setSlices(slices: List<Slice>) {
        this.slices = slices
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(320f).toInt(), widthSpec),
            resolveSize(dp(210f).toInt(), heightSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val total = slices.sumOf { it.value }
        if (slices.isEmpty() || total <= 0.0) {
            canvas.drawText(emptyText, w / 2f, height / 2f, emptyPaint)
            return
        }

        val ringStroke = dp(26f)
        arc.strokeWidth = ringStroke
        val diameter = dp(130f)
        val cx = diameter / 2f + dp(6f)
        val cy = diameter / 2f + dp(10f)
        val box = RectF(
            cx - diameter / 2f + ringStroke / 2f,
            cy - diameter / 2f + ringStroke / 2f,
            cx + diameter / 2f - ringStroke / 2f,
            cy + diameter / 2f - ringStroke / 2f,
        )

        var start = -90f   // start at twelve o'clock, where a reader starts
        slices.forEach { slice ->
            val sweep = (slice.value / total * 360.0).toFloat()
            arc.color = slice.colour
            // A hairline gap so adjacent slices stay distinguishable without
            // relying on colour alone.
            canvas.drawArc(box, start, (sweep - 1.2f).coerceAtLeast(0.6f), false, arc)
            start += sweep
        }

        canvas.drawText(shortMoney(total), cx, cy + dp(2f), totalPaint)
        if (centreCaption.isNotEmpty()) {
            canvas.drawText(centreCaption, cx, cy + dp(16f), captionPaint)
        }

        // Legend, to the right of the ring.
        val legendLeft = diameter + dp(22f)
        slices.forEachIndexed { i, slice ->
            val y = dp(28f) + i * dp(24f)
            swatch.color = slice.colour
            canvas.drawRoundRect(
                RectF(legendLeft, y - dp(9f), legendLeft + dp(11f), y + dp(2f)),
                dp(2f), dp(2f), swatch,
            )
            val share = slice.value / total * 100.0
            canvas.drawText(
                "${slice.label}  ${"%.0f".format(share)}%",
                legendLeft + dp(17f), y, legendPaint,
            )
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}


/**
 * A sparkline for a daily trend.
 *
 * Deliberately unlabelled. Its job is the SHAPE of the last few weeks; the
 * exact figures are the bar chart's job, and crowding both onto a phone
 * makes neither readable.
 */
@SuppressLint("ViewConstructor")
class SparklineView(
    context: Context,
    private var values: List<Double> = emptyList(),
    private val lineColour: Int = ChartPalette.indigo,
) : View(context) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = lineColour
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(28, Color.red(lineColour), Color.green(lineColour), Color.blue(lineColour))
    }

    fun setValues(values: List<Double>) {
        this.values = values
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(320f).toInt(), widthSpec),
            resolveSize(dp(56f).toInt(), heightSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (values.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(4f)
        val max = values.max().coerceAtLeast(1.0)
        val step = w / (values.size - 1)

        val path = android.graphics.Path()
        val area = android.graphics.Path()
        values.forEachIndexed { i, value ->
            val x = step * i
            val y = h - pad - ((value / max) * (h - pad * 2)).toFloat()
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(w, h)
        area.close()

        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
