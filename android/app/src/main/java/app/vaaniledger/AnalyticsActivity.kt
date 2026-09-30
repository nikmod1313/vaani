package app.vaaniledger

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.vaaniledger.data.LedgerDatabase
import app.vaaniledger.data.LineItemBackfill
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
import app.vaaniledger.ui.BarChartView
import app.vaaniledger.ui.ChartPalette
import app.vaaniledger.ui.DonutChartView
import app.vaaniledger.ui.RankBarView
import app.vaaniledger.ui.SparklineView
import app.vaaniledger.ui.shortMoney
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The business report.
 *
 * Four views the shopkeeper switches between, every figure computed from
 * Room. No network call anywhere on this page -- the same commitment the
 * extraction engine now keeps, and for the same reason: this is most useful
 * standing at a counter, which is exactly where the signal is worst.
 *
 * WHAT IS MEASURED, AND WHAT IS ESTIMATED. Sales, payment mix, entry counts
 * and customer totals are exact -- they come straight off transaction rows.
 * Per-product revenue is exact only where the message stated a rate; where it
 * did not, a bill is split evenly across its items, because a two-item order
 * with a single total genuinely does not say which item carried it. The page
 * says so in as many words rather than presenting an estimate as a fact.
 */
class AnalyticsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val paper = Color.rgb(0xF7, 0xF6, 0xF3)
    private val ink = ChartPalette.ink
    private val muted = ChartPalette.muted
    private val indigo = ChartPalette.indigo

    private lateinit var content: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var rangeRow: LinearLayout

    private var tab = Tab.OVERVIEW
    private var range = Range.YEAR

    private enum class Tab { OVERVIEW, SALES, PRODUCTS, CUSTOMERS }

    /** Window the page reports on. `since` is epoch millis, 0 meaning all time. */
    private enum class Range(val months: Int) { QUARTER(3), YEAR(12), ALL(0) }

    private fun since(): Long {
        if (range == Range.ALL) return 0L
        return Calendar.getInstance().apply {
            add(Calendar.MONTH, -range.months)
        }.timeInMillis
    }

    private fun strings() = Strings.of(ShopSettings.language(this)).analytics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = strings()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            setPadding(dp(18), dp(32), dp(18), dp(28))
        }

        root.addView(TextView(this).apply {
            text = s.title
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = s.subtitle
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(3), 0, dp(16))
        })

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        })

        rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(rangeRow)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(content)

        renderChips()
        setContentView(ScrollView(this).apply { addView(root) })
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------------------

    private fun renderChips() {
        val s = strings()
        tabRow.removeAllViews()
        listOf(
            Tab.OVERVIEW to s.tabOverview,
            Tab.SALES to s.tabSales,
            Tab.PRODUCTS to s.tabProducts,
            Tab.CUSTOMERS to s.tabCustomers,
        ).forEach { (value, label) ->
            tabRow.addView(chip(label, value == tab) { tab = value; renderChips(); load() })
        }

        rangeRow.removeAllViews()
        listOf(
            Range.QUARTER to s.range3m,
            Range.YEAR to s.range12m,
            Range.ALL to s.rangeAll,
        ).forEach { (value, label) ->
            rangeRow.addView(chip(label, value == range, small = true) {
                range = value; renderChips(); load()
            })
        }
    }

    private fun load() {
        val s = strings()
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = "…"
            setTextColor(muted)
            setPadding(0, dp(30), 0, dp(30))
            gravity = Gravity.CENTER
        })

        scope.launch {
            // Entries saved before line items had their own table would
            // otherwise leave the product charts empty on a shop that has
            // plainly been selling things.
            runCatching { LineItemBackfill.runIfNeeded(this@AnalyticsActivity) }

            val db = LedgerDatabase.get(this@AnalyticsActivity)
            val dao = db.analytics()
            val shop = ShopSettings.ID
            val from = since()

            val data = runCatching {
                Loaded(
                    headline = dao.headline(shop, from),
                    monthly = dao.monthly(shop, 12).reversed(),
                    quarterly = dao.quarterly(shop, 8).reversed(),
                    annual = dao.annual(shop, 5).reversed(),
                    daily = dao.daily(shop, sinceDays(45)),
                    mix = dao.paymentMix(shop, from),
                    byRevenue = dao.topProductsByRevenue(shop, from),
                    byQuantity = dao.topProductsByQuantity(shop, from),
                    customers = dao.topCustomers(shop, from),
                )
            }.onFailure { android.util.Log.e(TAG, "analytics load failed", it) }.getOrNull()

            withContext(Dispatchers.Main) {
                content.removeAllViews()
                if (data == null || data.headline.entries == 0) {
                    content.addView(emptyState(s.empty))
                    return@withContext
                }
                when (tab) {
                    Tab.OVERVIEW -> renderOverview(data)
                    Tab.SALES -> renderSales(data)
                    Tab.PRODUCTS -> renderProducts(data)
                    Tab.CUSTOMERS -> renderCustomers(data)
                }
            }
        }
    }

    private fun sinceDays(days: Int): Long =
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }.timeInMillis

    private class Loaded(
        val headline: app.vaaniledger.data.Headline,
        val monthly: List<app.vaaniledger.data.PeriodTotal> = emptyList(),
        val quarterly: List<app.vaaniledger.data.PeriodTotal> = emptyList(),
        val annual: List<app.vaaniledger.data.PeriodTotal> = emptyList(),
        val daily: List<app.vaaniledger.data.PeriodTotal> = emptyList(),
        val mix: List<app.vaaniledger.data.ModeTotal> = emptyList(),
        val byRevenue: List<app.vaaniledger.data.ProductTotal> = emptyList(),
        val byQuantity: List<app.vaaniledger.data.ProductTotal> = emptyList(),
        val customers: List<app.vaaniledger.data.CustomerTotal> = emptyList(),
    )

    // ----------------------------------------------------------------------

    private fun renderOverview(data: Loaded) {
        val s = strings()
        val h = data.headline

        content.addView(kpiGrid(
            listOf(
                Triple(s.kpiSale, shortMoney(h.sale), indigo),
                Triple(s.kpiCashIn, shortMoney(h.cashIn), ChartPalette.good),
                Triple(s.kpiUdhaar, shortMoney(h.udhaar), ChartPalette.warn),
                Triple(s.kpiEntries, h.entries.toString(), ink),
                Triple(s.kpiCustomers, h.customers.toString(), ink),
                Triple(
                    s.kpiAvgOrder,
                    if (h.entries > 0) shortMoney(h.sale / h.entries) else "—",
                    ink,
                ),
            )
        ))

        if (data.daily.size >= 2) {
            content.addView(card(s.dailyTrend) {
                SparklineView(this, data.daily.map { it.sale })
            })
        }
        content.addView(card(s.paymentMix) { donut(data) })
    }

    private fun renderSales(data: Loaded) {
        val s = strings()
        content.addView(card(s.monthly) {
            BarChartView(this, emptyText = s.empty).apply {
                setData(data.monthly.map { shortPeriod(it.period) }, data.monthly.map { it.sale })
            }
        })
        content.addView(card(s.quarterly) {
            BarChartView(this, emptyText = s.empty, barColour = ChartPalette.good).apply {
                setData(data.quarterly.map { it.period.substringAfter('-') }, data.quarterly.map { it.sale })
            }
        })
        content.addView(card(s.annual) {
            BarChartView(this, emptyText = s.empty, barColour = ChartPalette.saffron).apply {
                setData(data.annual.map { it.period }, data.annual.map { it.sale })
            }
        })
    }

    private fun renderProducts(data: Loaded) {
        val s = strings()
        content.addView(card(s.topByRevenue) {
            RankBarView(this, emptyText = s.empty).apply {
                setRows(data.byRevenue.map {
                    RankBarView.Row(it.displayName, it.revenue, s.ordersCaption(it.orders))
                })
            }
        })
        content.addView(card(s.topByQuantity) {
            RankBarView(this, emptyText = s.empty).apply {
                setRows(data.byQuantity.map {
                    RankBarView.Row(
                        it.displayName,
                        it.revenue,
                        s.soldCaption(trimQty(it.quantity)),
                    )
                })
            }
        })
        content.addView(note(s.estimatedNote))
    }

    private fun renderCustomers(data: Loaded) {
        val s = strings()
        content.addView(card(s.topCustomers) {
            RankBarView(this, emptyText = s.empty).apply {
                setRows(data.customers.map {
                    RankBarView.Row(it.name, it.revenue, s.ordersCaption(it.orders))
                })
            }
        })
    }

    private fun donut(data: Loaded): View {
        val s = strings()
        val colours = mapOf(
            "cash" to ChartPalette.good,
            "upi" to indigo,
            "udhaar" to ChartPalette.warn,
        )
        val labels = mapOf(
            "cash" to s.modeCash, "upi" to s.modeUpi, "udhaar" to s.modeUdhaar,
        )
        return DonutChartView(
            this,
            centreCaption = s.kpiSale,
            emptyText = s.empty,
        ).apply {
            setSlices(data.mix.map {
                DonutChartView.Slice(
                    labels[it.mode] ?: it.mode,
                    it.total,
                    colours[it.mode] ?: muted,
                )
            })
        }
    }

    // ---- building blocks --------------------------------------------------

    private fun kpiGrid(cells: List<Triple<String, String, Int>>): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            cells.chunked(2).forEach { pair ->
                addView(LinearLayout(this@AnalyticsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    pair.forEachIndexed { i, (label, value, colour) ->
                        if (i > 0) addView(
                            View(this@AnalyticsActivity),
                            LinearLayout.LayoutParams(dp(10), 1),
                        )
                        addView(kpi(label, value, colour), equalWeight())
                    }
                    if (pair.size == 1) {
                        addView(View(this@AnalyticsActivity), LinearLayout.LayoutParams(dp(10), 1))
                        addView(View(this@AnalyticsActivity), equalWeight())
                    }
                    (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(10)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) })
            }
        }

    private fun kpi(label: String, value: String, colour: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), Color.rgb(0xDF, 0xDC, 0xD5))
            }
            addView(TextView(this@AnalyticsActivity).apply {
                text = label
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@AnalyticsActivity).apply {
                text = value
                setTextColor(colour)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(3), 0, 0)
            })
        }

    private fun card(title: String, body: () -> View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(0xDF, 0xDC, 0xD5))
            }
            addView(TextView(this@AnalyticsActivity).apply {
                text = title
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(12))
            })
            addView(body())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }

    private fun note(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(dp(2), 0, dp(2), dp(8))
    }

    private fun emptyState(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(50), dp(20), dp(50))
    }

    private fun chip(label: String, selected: Boolean, small: Boolean = false, onClick: () -> Unit): View =
        TextView(this).apply {
            text = label
            setTextColor(if (selected) Color.WHITE else ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 11f else 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(if (small) 12 else 15), dp(8), dp(if (small) 12 else 15), dp(8))
            background = GradientDrawable().apply {
                setColor(if (selected) indigo else Color.WHITE)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), if (selected) indigo else Color.rgb(0xDF, 0xDC, 0xD5))
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(8) }
        }

    private fun equalWeight() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    /** "2026-09" -> "Sep". The year is implied by the run of bars. */
    private fun shortPeriod(period: String): String {
        val month = period.substringAfter('-', "").toIntOrNull() ?: return period
        val names = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
        return names.getOrElse(month - 1) { period }
    }

    private fun trimQty(q: Double): String =
        if (q == Math.floor(q)) q.toInt().toString() else "%.1f".format(q)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "VaaniAnalytics"
    }
}
