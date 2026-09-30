package app.vaaniledger.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One palette, one set of building blocks.
 *
 * Colours used to be written inline in twelve files, which produced three
 * slightly different greys and two different blacks -- the kind of drift
 * nobody notices in one screen and everybody feels across five.
 *
 * THE SCHEME. Deep oxblood, warm paper, brass. A bahi khata is a red
 * cloth-bound book, so the app's own metaphor supplies its colour; it reads
 * as serious money rather than software, and warm paper is easier to look at
 * under a shop's tube light than the cool grey it replaces.
 *
 * Semantic colours never change meaning: green is money in, amber is money
 * owed, red is a problem. They are also never the ONLY signal -- every
 * amount carries a sign or a word beside it, because a shopkeeper reading a
 * khata in daylight should not have to distinguish two similar hues to know
 * whether they are owed.
 */
object Theme {

    // ---- brand ----------------------------------------------------------
    /** Oxblood. Headers, primary actions, the selected state. */
    val primary = Color.rgb(0x7B, 0x2D, 0x26)
    val primaryDeep = Color.rgb(0x5C, 0x21, 0x1C)
    /** A tint of the primary, for selected rows and soft fills. */
    val primarySoft = Color.rgb(0xF6, 0xEC, 0xEA)
    /** Brass. Accents and the third chart series. */
    val accent = Color.rgb(0xA8, 0x76, 0x1C)

    // ---- surfaces -------------------------------------------------------
    /** Warm off-white, the page behind everything. */
    val paper = Color.rgb(0xF7, 0xF5, 0xF0)
    val surface = Color.WHITE
    /** Secondary buttons and inactive fills. */
    val subtle = Color.rgb(0xF0, 0xED, 0xE7)
    val border = Color.rgb(0xE4, 0xDF, 0xD6)

    // ---- text -----------------------------------------------------------
    /** Warm near-black. A cool black on warm paper looks like a printing error. */
    val ink = Color.rgb(0x1A, 0x16, 0x13)
    /**
     * Captions and secondary text.
     *
     * Darker than it first appears to need to be, deliberately. The value
     * originally chosen scored 4.3:1 against paper, under the 4.5 minimum,
     * and this colour carries every caption in the app at 10-13sp. It now
     * scores 5.4:1. These numbers get read in daylight, on cheap screens, by
     * people who are often long-sighted.
     */
    val muted = Color.rgb(0x6B, 0x64, 0x5C)

    // ---- semantic -------------------------------------------------------
    /** Money in. */
    val good = Color.rgb(0x1F, 0x6B, 0x45)
    val goodSoft = Color.rgb(0xE6, 0xF1, 0xEA)
    /** Money owed. */
    val warn = Color.rgb(0xA2, 0x69, 0x1B)
    val warnSoft = Color.rgb(0xFB, 0xF0, 0xDA)
    /** Something wrong. */
    val danger = Color.rgb(0xA3, 0x34, 0x2A)

    /** Chart series, in assignment order. Distinct in hue AND in lightness. */
    val series = intArrayOf(primary, good, accent, Color.rgb(0x4A, 0x5A, 0x6B), Color.rgb(0x6B, 0x4A, 0x6B))
}


/**
 * Shared view builders.
 *
 * Extension functions on Context so every activity and overlay can use them
 * without inheriting from anything -- the overlay views are not Activities
 * and never can be.
 */
object Views {

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** A white card with the standard radius, border and bottom margin. */
    fun Context.card(padding: Int = 14, marginBottom: Int = 12): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
            background = GradientDrawable().apply {
                setColor(Theme.surface)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Theme.border)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(marginBottom) }
        }

    fun Context.title(text: String, size: Float = 26f): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Theme.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = Typeface.DEFAULT_BOLD
        }

    fun Context.body(text: String, size: Float = 13f, colour: Int = Theme.muted): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(colour)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }

    /** A small all-caps heading over a group of cards. */
    fun Context.sectionHeading(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Theme.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, dp(18), 0, dp(10))
        }

    /** A pill. Used for tabs and filters. */
    fun Context.chip(
        label: String,
        selected: Boolean,
        small: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        text = label
        setTextColor(if (selected) Color.WHITE else Theme.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 11f else 13f)
        typeface = Typeface.DEFAULT_BOLD
        val h = if (small) 12 else 15
        setPadding(dp(h), dp(8), dp(h), dp(8))
        background = GradientDrawable().apply {
            setColor(if (selected) Theme.primary else Theme.surface)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), if (selected) Theme.primary else Theme.border)
        }
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(8) }
    }

    /** A full-width action button. */
    fun Context.button(
        label: String,
        primary: Boolean = true,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        text = label
        setTextColor(if (primary) Color.WHITE else Theme.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(if (primary) Theme.primary else Theme.subtle)
            cornerRadius = dp(12).toFloat()
        }
        isClickable = true
        setOnClickListener { onClick() }
    }
}


/**
 * The bottom navigation bar.
 *
 * Four destinations, always visible, always in the same order. A shopkeeper
 * who has learned that the report is third should never find it somewhere
 * else, so the bar is built from one list and the current page is passed in
 * rather than tracked anywhere.
 *
 * Labels sit under the icons rather than being implied by them. An icon-only
 * bar asks the user to have learned a glyph vocabulary, which is a poor bet
 * for the audience this is built for.
 */
object BottomNav {

    enum class Destination { HOME, REPORT, LEDGER, SETTINGS }

    data class Item(val destination: Destination, val icon: String, val label: String)

    /**
     * Build the bar.
     *
     * @param onNavigate called for a destination that is NOT the current one;
     *   re-tapping the current tab does nothing, which is what stops a stack
     *   of identical activities building up behind the user.
     */
    fun build(
        activity: Activity,
        current: Destination,
        items: List<Item>,
        onNavigate: (Destination) -> Unit,
    ): View {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.surface)
            elevation = dp(12).toFloat()
            setPadding(dp(6), dp(8), dp(6), dp(10))

            // A hairline above the bar so it reads as a separate surface even
            // when the content behind it is also white.
            val wrapper = this
            items.forEach { item ->
                val selected = item.destination == current
                wrapper.addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                        isClickable = true
                        background = GradientDrawable().apply {
                            setColor(if (selected) Theme.primarySoft else Color.TRANSPARENT)
                            cornerRadius = dp(12).toFloat()
                        }
                        setOnClickListener { if (!selected) onNavigate(item.destination) }

                        addView(TextView(activity).apply {
                            text = item.icon
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                            gravity = Gravity.CENTER
                            // Selection is carried by the tinted pill, the
                            // label weight AND the colour together, so it does
                            // not rest on hue alone.
                            alpha = if (selected) 1f else 0.55f
                        })
                        addView(TextView(activity).apply {
                            text = item.label
                            setTextColor(if (selected) Theme.primary else Theme.muted)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                            gravity = Gravity.CENTER
                            setPadding(0, dp(2), 0, 0)
                        })
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        }
    }
}


/**
 * A page: scrolling content above, navigation pinned below.
 *
 * Every top-level screen is built through here so the bar cannot drift out of
 * alignment between pages, and so content can never scroll underneath it.
 */
object Scaffold {

    fun Activity.page(
        current: BottomNav.Destination,
        items: List<BottomNav.Item>,
        onNavigate: (BottomNav.Destination) -> Unit,
        content: View,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Theme.paper)

        addView(
            ScrollView(this@page).apply {
                isFillViewport = true
                addView(content)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )

        // The hairline is its own view rather than a border on the bar, so it
        // stays exactly one pixel at every density.
        addView(
            View(this@page).apply { setBackgroundColor(Theme.border) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
        )
        addView(BottomNav.build(this@page, current, items, onNavigate))
    }

    /**
     * Swap to another top-level page.
     *
     * REORDER_TO_FRONT plus NO_ANIMATION: tabs should feel like one app
     * switching views, not like a stack of screens piling up that the back
     * button then has to unwind one at a time.
     */
    fun Activity.navigateTo(target: Class<out Activity>) {
        startActivity(
            Intent(this, target).addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        )
        overridePendingTransition(0, 0)
    }
}
