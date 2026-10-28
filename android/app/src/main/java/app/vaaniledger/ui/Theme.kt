package app.vaaniledger.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.vaaniledger.R
import app.vaaniledger.ui.Views.fitSystemBars

/**
 * One palette, one set of building blocks.
 *
 * THE SCHEME -- "neel aur sona", indigo and gold. Taken from the things a
 * Rajasthani haveli is made of rather than from software: the indigo of a
 * blueprint-printed palace wall, the gold of a temple sun, the oxblood of a
 * bahi khata's cloth binding, turquoise from a painted door, and aged
 * parchment behind all of it. Money should look like it is written in a
 * book someone keeps carefully, not on a dashboard.
 *
 * Semantic colours never change meaning: green is money in, saffron is money
 * owed, red is a problem. They are also never the ONLY signal -- every
 * amount carries a sign or a word beside it, because a shopkeeper reading a
 * khata in daylight should not have to tell two similar hues apart to know
 * whether they are owed.
 *
 * Every text colour below is checked against the parchment it sits on and
 * clears 4.5:1; these numbers get read on cheap screens under tube light.
 */
object Theme {

    // ---- brand ----------------------------------------------------------
    /** Blueprint indigo. Headers, primary actions, the selected state. */
    val primary = Color.rgb(0x1B, 0x2A, 0x5C)
    val primaryDeep = Color.rgb(0x11, 0x1C, 0x42)
    /** A wash of indigo, for selected rows and soft fills. */
    val primarySoft = Color.rgb(0xE4, 0xE3, 0xEA)
    /** Temple gold, for rules and ornaments on parchment. */
    val accent = Color.rgb(0xA8, 0x7A, 0x2A)
    /** The brighter gold, used only on indigo where the darker one sinks. */
    val gold = Color.rgb(0xD9, 0xB0, 0x5E)
    val goldPale = Color.rgb(0xF0, 0xDC, 0xA8)
    /** Bahi-khata cloth. Udhaar reminders and the odd emphasis. */
    val oxblood = Color.rgb(0x7A, 0x1F, 0x2B)
    /** The painted door. UPI, and the fourth chart series. */
    val turquoise = Color.rgb(0x1F, 0x7A, 0x86)

    // ---- surfaces -------------------------------------------------------
    /** Aged parchment, the page behind everything. */
    val paper = Color.rgb(0xF3, 0xEA, 0xD3)
    /** A fresher sheet laid on top: cards. */
    val surface = Color.rgb(0xFB, 0xF6, 0xEA)
    /** Inputs, quotes and inactive fills. */
    val subtle = Color.rgb(0xEC, 0xE1, 0xC6)
    /** The outer rule of a card: gold faded into paper. */
    val border = Color.rgb(0xDC, 0xCB, 0xA4)

    // ---- text -----------------------------------------------------------
    /** Iron-gall ink: a warm brown-black. Cool black on parchment looks printed by mistake. */
    val ink = Color.rgb(0x2A, 0x1F, 0x17)
    /** Captions and secondary text. 5.6:1 on paper. */
    val muted = Color.rgb(0x6A, 0x5A, 0x48)
    /** Text on indigo. */
    val onPrimary = Color.rgb(0xF6, 0xEE, 0xDA)
    val onPrimaryMuted = Color.argb(0xB8, 0xF6, 0xEE, 0xDA)

    // ---- semantic -------------------------------------------------------
    /** Money in. */
    val good = Color.rgb(0x2C, 0x63, 0x3F)
    val goodSoft = Color.rgb(0xDF, 0xE8, 0xD2)
    /** Money owed. */
    val warn = Color.rgb(0x9A, 0x5A, 0x12)
    val warnSoft = Color.rgb(0xF4, 0xE0, 0xB8)
    /** Something wrong. */
    val danger = Color.rgb(0x8E, 0x2A, 0x2A)

    /** Chart series, in assignment order. Distinct in hue AND in lightness. */
    val series = intArrayOf(primary, good, accent, turquoise, oxblood)
}


/**
 * The two typefaces, loaded once.
 *
 * Tiro Devanagari Hindi is a manuscript-tradition serif with a matching
 * Latin, so a heading reads the same whether it says "Khata" or "खाता".
 * Mukta is the working face: clear at 11sp, with Devanagari that holds up on
 * a low-density screen. Both ship in res/font under the SIL Open Font
 * Licence.
 */
object Type {
    private var cache: Map<Int, Typeface>? = null

    private fun get(context: Context, id: Int): Typeface {
        val loaded = cache ?: run {
            val r = context.applicationContext.resources
            mapOf(
                R.font.tiro_regular to r.getFont(R.font.tiro_regular),
                R.font.tiro_italic to r.getFont(R.font.tiro_italic),
                R.font.mukta_regular to r.getFont(R.font.mukta_regular),
                R.font.mukta_semibold to r.getFont(R.font.mukta_semibold),
                R.font.mukta_bold to r.getFont(R.font.mukta_bold),
            ).also { cache = it }
        }
        return loaded[id] ?: Typeface.DEFAULT
    }

    /** Headings, big figures, the wordmark. */
    fun display(c: Context) = get(c, R.font.tiro_regular)
    fun displayItalic(c: Context) = get(c, R.font.tiro_italic)
    fun body(c: Context) = get(c, R.font.mukta_regular)
    fun medium(c: Context) = get(c, R.font.mukta_semibold)
    fun bold(c: Context) = get(c, R.font.mukta_bold)
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
    fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

    fun matchWrap(bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = bottom }

    fun weight(w: Float = 1f) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)

    fun Context.text(
        text: CharSequence,
        size: Float,
        colour: Int = Theme.ink,
        face: Typeface = Type.body(this),
    ): TextView = TextView(this).apply {
        this.text = text
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = face
        includeFontPadding = true
        // Tiro's line box is tight; wrapped serif lines touch without a
        // little extra leading.
        if (face == Type.display(this@text) || face == Type.displayItalic(this@text)) {
            setLineSpacing(size * resources.displayMetrics.scaledDensity * 0.12f, 1f)
        }
    }

    /** A page or sheet heading, in the manuscript serif. */
    fun Context.title(text: String, size: Float = 30f, colour: Int = Theme.ink): TextView =
        text(text, size, colour, Type.display(this)).apply {
            setLineSpacing(0f, 1.0f)
        }

    fun Context.body(text: String, size: Float = 14f, colour: Int = Theme.muted): TextView =
        text(text, size, colour, Type.body(this)).apply {
            // Mukta's line box is sized for stacked Devanagari matras, which
            // leaves Latin text floating; pull it in a little.
            setLineSpacing(0f, 0.9f)
        }

    /**
     * The small gold capital line that sits over a title or a group of cards,
     * like the rubric in a bound register.
     */
    fun Context.eyebrow(text: String, colour: Int = Theme.accent): TextView =
        text(text.uppercase(), 11f, colour, Type.bold(this)).apply {
            letterSpacing = app.vaaniledger.ui.Views.tracking(text, 0.16f)
        }

    /** A section heading: eyebrow capitals followed by a gold hairline. */
    fun Context.sectionHeading(text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(22), 0, dp(12))
        addView(eyebrow(text))
        addView(OrnamentRule(context, flourish = false), LinearLayout.LayoutParams(0, dp(10), 1f).apply {
            marginStart = dp(10)
        })
    }

    /** Page header: eyebrow, serif title, and an optional line under it. */
    fun Context.pageHeader(eyebrow: String, title: String, subtitle: String? = null): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(eyebrow(eyebrow))
            addView(title(title).apply { setPadding(0, dp(4), 0, 0) })
            if (!subtitle.isNullOrBlank()) {
                addView(body(subtitle, 14f).apply { setPadding(0, dp(4), 0, 0) })
            }
        }

    /** A card: fresh paper with a gold double rule, like a ledger page's border. */
    fun Context.card(padding: Int = 16, marginBottom: Int = 12): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
            background = ManuscriptFrame(this@card)
            layoutParams = matchWrap(dp(marginBottom))
        }

    /** A pill. Used for tabs and filters. */
    fun Context.chip(
        label: String,
        selected: Boolean,
        small: Boolean = false,
        onClick: () -> Unit,
    ): TextView = text(label, if (small) 12f else 14f, if (selected) Theme.onPrimary else Theme.ink, Type.medium(this)).apply {
        val h = if (small) 13 else 16
        setPadding(dp(h), dp(if (small) 5 else 7), dp(h), dp(if (small) 5 else 7))
        background = GradientDrawable().apply {
            setColor(if (selected) Theme.primary else Color.TRANSPARENT)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), if (selected) Theme.primary else Theme.border)
        }
        pressable()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(8) }
    }

    /**
     * A segmented control: one rounded track, the chosen option a raised
     * indigo block inside it. Reads as "pick one of these" in a way a loose
     * row of pills never quite does.
     */
    fun <T> Context.segmented(
        options: List<Pair<T, String>>,
        selected: T,
        onPick: (T) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = GradientDrawable().apply {
            setColor(Theme.subtle)
            cornerRadius = dp(14).toFloat()
        }
        options.forEach { (value, label) ->
            val on = value == selected
            addView(text(label, 13f, if (on) Theme.onPrimary else Theme.muted, Type.medium(this@segmented)).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                // Devanagari sits taller in its line box than Latin, so a
                // "हिंदी | English" pair sized itself unevenly. Every cell is
                // the same width (weight) AND the same fixed height, with the
                // font's own padding removed, so the two halves always match.
                includeFontPadding = false
                setPadding(dp(6), 0, dp(6), 0)
                if (on) background = GradientDrawable().apply {
                    setColor(Theme.primary)
                    cornerRadius = dp(10).toFloat()
                }
                pressable()
                setOnClickListener { if (!on) onPick(value) }
            }, LinearLayout.LayoutParams(0, dp(36), 1f))
        }
    }

    /**
     * A full-width action button.
     *
     * Primary is indigo with a gold hairline inset inside it -- the seal on a
     * document. Secondary is an outline in ink, so the two never compete.
     */
    fun Context.button(
        label: String,
        primary: Boolean = true,
        icon: LineIcon.Glyph? = null,
        onClick: () -> Unit,
    ): TextView = text(label, 16f, if (primary) Theme.onPrimary else Theme.ink, Type.medium(this)).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = if (primary) SealFrame(this@button) else GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Theme.ink.withAlpha(0x55))
        }
        if (icon != null) {
            val d = LineIcon(this@button, icon, if (primary) Theme.gold else Theme.ink, sizeDp = 20)
            setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
            compoundDrawablePadding = dp(10)
        }
        pressable()
        setOnClickListener { onClick() }
    }

    /** A small status badge: a coloured word on its own soft tint. */
    fun Context.pill(label: String, colour: Int, fill: Int): TextView =
        text(label.uppercase(), 10.5f, colour, Type.bold(this)).apply {
            letterSpacing = app.vaaniledger.ui.Views.tracking(text, 0.08f)
            setPadding(dp(9), dp(2), dp(9), dp(2))
            background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(10).toFloat()
            }
        }

    /** An icon inside a soft round medallion, for list rows. */
    fun Context.medallion(glyph: LineIcon.Glyph, tint: Int = Theme.primary, fill: Int = Theme.subtle, size: Int = 44): View =
        FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
            }
            addView(View(this@medallion).apply {
                background = LineIcon(this@medallion, glyph, tint, sizeDp = (size * 0.5f).toInt())
            }, FrameLayout.LayoutParams(dp((size * 0.5f).toInt()), dp((size * 0.5f).toInt()), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }

    /** The "›" that says a row goes somewhere. */
    fun Context.chevron(tint: Int = Theme.muted): View = View(this).apply {
        background = LineIcon(this@chevron, LineIcon.Glyph.CHEVRON, tint, sizeDp = 18)
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) }
    }

    /** Spacer. */
    fun Context.gap(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    /**
     * Press feedback: a slight sink and dim while held.
     *
     * Every tappable surface in the app uses this, so the whole interface
     * answers a touch the same way -- without it, flat views on paper give no
     * sign a tap registered until the next screen arrives.
     */
    fun View.pressable() {
        isClickable = true
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.975f).scaleY(0.975f).alpha(0.86f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
            }
            false
        }
    }

    /**
     * Keep content clear of the status and navigation bars.
     *
     * The app targets an SDK where Android always draws edge to edge, so the
     * paper runs under the clock and the gesture bar -- which is right -- but
     * the writing must not. Padding is added to whatever the view already
     * had, so callers keep their own margins.
     */
    fun View.fitSystemBars(top: Boolean = true, bottom: Boolean = true) {
        val l = paddingLeft; val t = paddingTop; val r = paddingRight; val b = paddingBottom
        setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(l, t + if (top) insets.systemWindowInsetTop else 0, r,
                b + if (bottom) insets.systemWindowInsetBottom else 0)
            insets
        }
        requestApplyInsets()
    }

    /**
     * Letter-spacing that respects the script. Tracked capitals suit Latin;
     * spacing Devanagari pulls apart the shirorekha, the headline stroke that
     * joins a word's letters, and the word falls to pieces. So a label with
     * any Devanagari in it is set solid.
     */
    fun tracking(text: CharSequence?, amount: Float): Float =
        if (text?.any { it in '\u0900'..'\u097F' } == true) 0f else amount

    fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
}


/**
 * Entrance motion, kept to one gesture: content rises a few dp and fades in,
 * each block a beat after the one above. It is there to show the page is
 * arriving in order, top to bottom -- never long enough to wait for.
 */
object Motion {

    val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun stagger(group: ViewGroup, startDelay: Long = 0, step: Long = 45) {
        val rise = group.resources.displayMetrics.density * 14
        for (i in 0 until group.childCount) {
            group.getChildAt(i).apply { alpha = 0f; translationY = rise }
        }
        // Animations started on a view that is not yet attached can be
        // dropped, leaving it invisible for good -- so hide now, and only
        // start once the group has been laid out.
        if (!group.isLaidOut) {
            group.post { run(group, startDelay, step) }
        } else {
            run(group, startDelay, step)
        }
    }

    private fun run(group: ViewGroup, startDelay: Long, step: Long) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(startDelay + i * step)
                .setDuration(420)
                .setInterpolator(ease)
                .start()
        }
    }

    /** Count a figure up from zero; makes a total feel tallied rather than printed. */
    fun countUp(view: TextView, target: Double, format: (Double) -> String) {
        if (target <= 0.0) { view.text = format(0.0); return }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { view.text = format(target * (it.animatedValue as Float)) }
            start()
        }
    }
}


/**
 * The bottom navigation bar.
 *
 * Four destinations, always visible, always in the same order. A shopkeeper
 * who has learned that the report is second should never find it somewhere
 * else, so the bar is built from one list and the current page is passed in
 * rather than tracked anywhere.
 *
 * Labels sit under the icons rather than being implied by them. An icon-only
 * bar asks the user to have learned a glyph vocabulary, which is a poor bet
 * for the audience this is built for.
 */
object BottomNav {

    enum class Destination { HOME, REPORT, LEDGER, SETTINGS }

    data class Item(val destination: Destination, val icon: LineIcon.Glyph, val label: String)

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
            background = NavBarBackground(activity)
            setPadding(dp(8), dp(12), dp(8), dp(10))

            val wrapper = this
            items.forEach { item ->
                val selected = item.destination == current
                wrapper.addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(2), dp(4), dp(2))
                        isClickable = true
                        setOnClickListener { if (!selected) onNavigate(item.destination) }

                        // Selection is carried by the arch behind the icon,
                        // the label weight AND the colour together, so it
                        // does not rest on hue alone.
                        addView(FrameLayout(activity).apply {
                            if (selected) background = ArchDrawable(activity, Theme.gold.let {
                                Color.argb(0x33, Color.red(it), Color.green(it), Color.blue(it))
                            }, Theme.gold)
                            addView(View(activity).apply {
                                background = LineIcon(
                                    activity, item.icon,
                                    if (selected) Theme.goldPale else Theme.onPrimaryMuted,
                                    sizeDp = 22,
                                )
                            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER).apply {
                                topMargin = dp(4)
                            })
                        }, LinearLayout.LayoutParams(dp(46), dp(36)))
                        addView(TextView(activity).apply {
                            text = item.label
                            setTextColor(if (selected) Theme.goldPale else Theme.onPrimaryMuted)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                            typeface = if (selected) Type.bold(activity) else Type.body(activity)
                            gravity = Gravity.CENTER
                            setPadding(0, dp(3), 0, 0)
                        })
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        }
    }
}


/**
 * A page: scrolling content over parchment, navigation pinned below.
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
        background = PaperBackground(this@page, pattern = true)

        addView(
            ScrollView(this@page).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(content)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
        addView(BottomNav.build(this@page, current, items, onNavigate).apply {
            fitSystemBars(top = false, bottom = true)
        })
        fitSystemBars(top = true, bottom = false)
    }

    /** A page with no navigation bar: review, onboarding. Same paper, same scroll. */
    fun Activity.plainPage(content: View): View = FrameLayout(this).apply {
        background = PaperBackground(this@plainPage)
        addView(ScrollView(this@plainPage).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        })
        fitSystemBars()
    }

    /**
     * Swap to another top-level page.
     *
     * REORDER_TO_FRONT plus a short crossfade: tabs should feel like one app
     * changing views, not like a stack of screens piling up that the back
     * button then has to unwind one at a time.
     */
    fun Activity.navigateTo(target: Class<out Activity>) {
        startActivity(
            Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.vaani_fade_in, R.anim.vaani_fade_out)
    }
}
