package app.vaaniledger

import app.vaaniledger.ui.BottomNav
import app.vaaniledger.ui.Nav
import app.vaaniledger.ui.Scaffold.page

import app.vaaniledger.ui.Theme
import app.vaaniledger.ui.ArchWindow
import app.vaaniledger.ui.ArtView
import app.vaaniledger.ui.ManuscriptFrame
import app.vaaniledger.ui.Motion
import app.vaaniledger.ui.OrnamentRule
import app.vaaniledger.ui.Type
import app.vaaniledger.ui.Views.chip
import app.vaaniledger.ui.Views.body
import app.vaaniledger.ui.Views.dp
import app.vaaniledger.ui.Views.eyebrow
import app.vaaniledger.ui.Views.matchWrap
import app.vaaniledger.ui.Views.pageHeader
import app.vaaniledger.ui.Views.segmented
import app.vaaniledger.ui.Views.text

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

    private val paper = Theme.paper
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
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }

        root.addView(pageHeader(ShopSettings.name(this), s.title, s.subtitle).apply {
            setPadding(0, 0, 0, dp(18))
        })

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tabRow)

        rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(rangeRow)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(content)

        renderChips()
        setContentView(
            page(
                current = BottomNav.Destination.REPORT,
                items = Nav.items(this),
                onNavigate = { Nav.go(this, it) },
                content = root,
            )
        )
        load()
    }

    /** Refresh on every return: the tab is reordered to the front, not rebuilt. */
    override fun onResume() {
        super.onResume()
        if (resumedOnce) load() else resumedOnce = true
    }

    private var resumedOnce = false

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------------------

    private fun renderChips() {
        val s = strings()
        tabRow.removeAllViews()
        tabRow.addView(segmented(
            listOf(
                Tab.OVERVIEW to s.tabOverview,
                Tab.SALES to s.tabSales,
                Tab.PRODUCTS to s.tabProducts,
                Tab.CUSTOMERS to s.tabCustomers,
            ),
            tab,
        ) { tab = it; renderChips(); load() })

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
        content.addView(OrnamentRule(this).apply { alpha = 0.6f }, LinearLayout.LayoutParams(-1, dp(14)).apply {
            topMargin = dp(40); bottomMargin = dp(40)
        })

        scope.launch {
            // Entries saved before line items had their own table would
            // otherwise leave the product charts empty on a shop that has
            // plainly been selling things.
            runCatching { LineItemBackfill.runIfNeeded(this@AnalyticsActivity) }
            runCatching { LineItemBackfill.rekeyIfNeeded(this@AnalyticsActivity) }

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
                Motion.stagger(content, step = 60)
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

        content.addView(heroKpi(s.kpiSale, h.sale, s.paymentMix))
        content.addView(kpiGrid(
            listOf(
                Triple(s.kpiCashIn, rupees(h.cashIn), ChartPalette.good),
                Triple(s.kpiUdhaar, rupees(h.udhaar), ChartPalette.warn),
                Triple(s.kpiEntries, h.entries.toString(), ink),
                Triple(s.kpiCustomers, h.customers.toString(), ink),
                Triple(
                    s.kpiAvgOrder,
                    if (h.entries > 0) rupees(h.sale / h.entries) else "—",
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

    /** The one figure that matters most, on the indigo palace panel. */
    private fun heroKpi(label: String, value: Double, caption: String): View {
        val panel = ArchWindow(this, R.drawable.art_palace, arched = false, focusY = 0.9f, shade = 0.9f)
        val figure = text("₹0", 40f, Theme.onPrimary, Type.display(this))
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            addView(eyebrow(label, Theme.gold))
            addView(figure.apply { setPadding(0, dp(6), 0, 0) })
            addView(text(rangeLabel(), 13f, Theme.onPrimaryMuted))
        })
        Motion.countUp(figure, value) { "₹%,.0f".format(it) }
        panel.layoutParams = matchWrap(dp(12))
        return panel
    }

    /**
     * The full figure, to the rupee. A tile has room for it, and "₹1k" for a
     * Rs 1,240 udhaar is a rounding the shopkeeper has to undo in their head;
     * the short form is for chart axes, which do not.
     */
    private fun rupees(v: Double) = "₹%,.0f".format(v)

    private fun rangeLabel(): String {
        val s = strings()
        return when (range) {
            Range.QUARTER -> s.range3m
            Range.YEAR -> s.range12m
            Range.ALL -> s.rangeAll
        }
    }

    private fun kpiGrid(cells: List<Triple<String, String, Int>>): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            cells.chunked(2).forEach { pair ->
                addView(LinearLayout(this@AnalyticsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    pair.forEachIndexed { i, (label, value, colour) ->
                        if (i > 0) addView(
                            View(this@AnalyticsActivity),
                            LinearLayout.LayoutParams(dp(12), 1),
                        )
                        addView(kpi(label, value, colour), equalWeight())
                    }
                    if (pair.size == 1) {
                        addView(View(this@AnalyticsActivity), LinearLayout.LayoutParams(dp(12), 1))
                        addView(View(this@AnalyticsActivity), equalWeight())
                    }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(12) })
            }
        }

    private fun kpi(label: String, value: String, colour: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ManuscriptFrame(this@AnalyticsActivity)
            addView(eyebrow(label, Theme.muted).apply { textSize = 10f })
            addView(text(value, 24f, colour, Type.display(this@AnalyticsActivity)).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun card(title: String, body: () -> View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = ManuscriptFrame(this@AnalyticsActivity)
            addView(text(title, 19f, ink, Type.display(this@AnalyticsActivity)))
            addView(OrnamentRule(this@AnalyticsActivity, flourish = false), LinearLayout.LayoutParams(-1, dp(8)).apply {
                topMargin = dp(6); bottomMargin = dp(14)
            })
            addView(body())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }

    private fun note(text: String): View = text(text, 12.5f, muted, Type.displayItalic(this)).apply {
        setPadding(dp(4), dp(2), dp(4), dp(8))
    }

    /** Nothing sold yet: the elephant, waiting, and one line on how to start. */
    private fun emptyState(text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(30), dp(20), dp(40))
        addView(ArtView(this@AnalyticsActivity, R.drawable.art_elephant).apply { alpha = 0.9f },
            LinearLayout.LayoutParams(dp(150), dp(170)))
        addView(OrnamentRule(this@AnalyticsActivity), LinearLayout.LayoutParams(dp(180), dp(14)).apply {
            topMargin = dp(18); bottomMargin = dp(14)
        })
        addView(body(text, 15f).apply { gravity = Gravity.CENTER })
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

    private companion object {
        const val TAG = "VaaniAnalytics"
    }
}
