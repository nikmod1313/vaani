package app.vaaniledger

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.vaaniledger.data.AppLanguage
import app.vaaniledger.data.ShopCategory
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
import app.vaaniledger.data.labelIn
import app.vaaniledger.ui.ArchDrawable
import app.vaaniledger.ui.ArchWindow
import app.vaaniledger.ui.ArtView
import app.vaaniledger.ui.LineIcon
import app.vaaniledger.ui.ManuscriptFrame
import app.vaaniledger.ui.Motion
import app.vaaniledger.ui.OrnamentRule
import app.vaaniledger.ui.Scaffold.plainPage
import app.vaaniledger.ui.Theme
import app.vaaniledger.ui.Type
import app.vaaniledger.ui.Views.body
import app.vaaniledger.ui.Views.button
import app.vaaniledger.ui.Views.dp
import app.vaaniledger.ui.Views.eyebrow
import app.vaaniledger.ui.Views.gap
import app.vaaniledger.ui.Views.matchWrap
import app.vaaniledger.ui.Views.pressable
import app.vaaniledger.ui.Views.segmented
import app.vaaniledger.ui.Views.text
import app.vaaniledger.ui.Views.title
import app.vaaniledger.ui.Views.weight
import app.vaaniledger.ui.Views.withAlpha
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * First-run setup. Runs exactly once, ever.
 *
 * Everything here answers one complaint: the category picker used to live on
 * the main screen as a card that looked exactly like the two permission
 * cards above it, and asked again every time that screen was drawn. A
 * shopkeeper cannot tell "a setting I already chose" apart from "a
 * permission I have not granted yet" when they are styled identically, and
 * being asked the same question on every visit reads as the app not
 * remembering the answer.
 *
 * [ShopSettings.hasCompletedOnboarding] is the gate: MainActivity checks it
 * before drawing anything and redirects here until both questions are
 * answered, then never again. Changing the answer later is a deliberate
 * settings action, not something this screen offers.
 *
 * ONE DECISION PER SCREEN. Each step asks exactly one thing with large,
 * illustrated choices -- the interface this app needs is for someone running
 * a shop counter, not someone comfortable with a form.
 *
 * The language can also be flipped on the welcome page itself, before any
 * question is asked: someone who reads English should not have to get
 * through two Hinglish screens to reach the one that lets them say so. The
 * choice is held here and only written when the last step is answered.
 */
class OnboardingActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var step = 0
    private var chosenCategory: ShopCategory? = null
    // Defaults the WELCOME screen to Hinglish, since that is the language it
    // is written in before anyone has chosen anything.
    private var chosenLanguage: AppLanguage = AppLanguage.HI

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(32))
        }
        setContentView(plainPage(root))
        render(direction = 0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Back walks back through the steps before it leaves. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (step > 0) go(step - 1) else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun go(next: Int) {
        val direction = if (next > step) 1 else -1
        step = next
        render(direction)
    }

    // ----------------------------------------------------------------------

    /**
     * Draw the current step. [direction] slides it in from the side it came
     * from: forward from the right, back from the left, so the order of the
     * steps is something the hand can feel.
     */
    private fun render(direction: Int) {
        root.removeAllViews()
        val bundle = Strings.of(chosenLanguage)

        if (step > 0) root.addView(stepBar(bundle.onboarding))

        when (step) {
            0 -> welcome(bundle)
            1 -> categoryStep(bundle)
            2 -> languageStep(bundle)
        }

        if (direction == 0) {
            Motion.stagger(root, startDelay = 80, step = 70)
        } else {
            root.alpha = 0f
            root.translationX = dp(28).toFloat() * direction
            root.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(Motion.ease).start()
        }
    }

    /** Back arrow, "Step 1 / 2", and a gold segment per step. */
    private fun stepBar(o: Strings.Onboarding): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(26))

        addView(View(this@OnboardingActivity).apply {
            background = LineIcon(this@OnboardingActivity, LineIcon.Glyph.BACK, Theme.ink, sizeDp = 22)
            contentDescription = o.back
            pressable()
            setOnClickListener { go(step - 1) }
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })

        addView(eyebrow(o.stepOf(step, TOTAL_STEPS), Theme.muted))
        addView(View(this@OnboardingActivity), LinearLayout.LayoutParams(0, 1, 1f))

        for (i in 1..TOTAL_STEPS) {
            addView(View(this@OnboardingActivity).apply {
                background = GradientDrawable().apply {
                    setColor(if (i <= step) Theme.accent else Theme.accent.withAlpha(0x40))
                    cornerRadius = dp(2).toFloat()
                }
            }, LinearLayout.LayoutParams(dp(if (i == step) 30 else 18), dp(4)).apply { marginStart = dp(5) })
        }
    }

    // ---- step 0: welcome --------------------------------------------------

    private fun welcome(t: Strings.Bundle) {
        val o = t.onboarding

        // Language, first and small, top right.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("वाणी", 22f, Theme.primary, Type.display(this@OnboardingActivity)), weight())
            addView(segmented(
                AppLanguage.entries.map { it to it.displayName },
                chosenLanguage,
            ) { chosenLanguage = it; render(direction = 0) }, LinearLayout.LayoutParams(dp(184), -2))
        }, matchWrap(dp(22)))

        // The palace through an arch, with the elephant stepping out of it --
        // a collage, as the reference boards are, rather than a framed photo.
        val heroHeight = (resources.displayMetrics.heightPixels / resources.displayMetrics.density * 0.40f)
            .toInt().coerceIn(260, 380)
        root.addView(FrameLayout(this).apply {
            addView(ArchWindow(this@OnboardingActivity, R.drawable.art_palace, arched = true, focusY = 0.85f),
                FrameLayout.LayoutParams(-1, dp(heroHeight)).apply {
                    marginStart = dp(26); marginEnd = dp(26)
                })
            addView(ArtView(this@OnboardingActivity, R.drawable.art_elephant),
                FrameLayout.LayoutParams(dp(150), dp(170), Gravity.BOTTOM or Gravity.END))
            addView(ArtView(this@OnboardingActivity, R.drawable.art_oud),
                FrameLayout.LayoutParams(dp(66), dp(106), Gravity.BOTTOM or Gravity.START).apply {
                    bottomMargin = dp(4)
                })
        }, matchWrap(dp(26)).apply { height = dp(heroHeight + 24) })

        root.addView(eyebrow(if (chosenLanguage == AppLanguage.EN) "Namaste" else "नमस्ते"))
        root.addView(title(o.welcomeTitle, 32f).apply { setPadding(0, dp(6), 0, dp(10)) })
        root.addView(body(o.welcomeSubtitle, 15f))
        root.addView(OrnamentRule(this), LinearLayout.LayoutParams(-1, dp(14)).apply {
            topMargin = dp(22); bottomMargin = dp(22)
        })
        root.addView(button(o.welcomeStart, primary = true) { go(1) })
    }

    // ---- step 1: category --------------------------------------------------

    private fun categoryStep(t: Strings.Bundle) {
        val o = t.onboarding
        root.addView(title(o.categoryTitle, 28f))
        root.addView(body(o.categorySubtitle).apply { setPadding(0, dp(8), 0, dp(24)) })

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ShopCategory.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(12))
            }
            pair.forEach { cat ->
                row.addView(
                    categoryTile(cat, selected = cat == chosenCategory) { tile ->
                        chosenCategory = cat
                        // Show the choice land before moving on, so the tap
                        // visibly did something rather than the page jumping.
                        tile.background = ArchDrawable(this, Theme.primary, Theme.gold)
                        tile.postDelayed({ go(2) }, 260)
                    },
                    LinearLayout.LayoutParams(0, dp(146), 1f)
                        .apply { marginEnd = if (cat == pair.first() && pair.size > 1) dp(12) else 0 },
                )
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(12) })
            grid.addView(row)
        }
        root.addView(grid)
    }

    private fun categoryTile(category: ShopCategory, selected: Boolean, onPick: (View) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setPadding(dp(10), dp(30), dp(10), dp(16))
            background = if (selected) ArchDrawable(this@OnboardingActivity, Theme.primary, Theme.gold)
            else ArchDrawable(this@OnboardingActivity, Theme.surface, Theme.border)
            pressable()
            setOnClickListener { onPick(this) }

            val (english, local) = category.labelIn(chosenLanguage).split(" / ").let {
                (it.getOrNull(1) ?: it[0]) to it[0]
            }
            addView(View(this@OnboardingActivity).apply {
                background = LineIcon(this@OnboardingActivity, glyphFor(category),
                    if (selected) Theme.gold else Theme.primary, sizeDp = 30)
            }, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(text(local, 16f, if (selected) Theme.onPrimary else Theme.ink, Type.display(this@OnboardingActivity)).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
            if (english != local) addView(text(english, 12f, if (selected) Theme.onPrimaryMuted else Theme.muted).apply {
                gravity = Gravity.CENTER
            })
        }

    private fun glyphFor(category: ShopCategory): LineIcon.Glyph = when (category) {
        ShopCategory.KIRANA -> LineIcon.Glyph.SACK
        ShopCategory.APPAREL -> LineIcon.Glyph.KURTA
        ShopCategory.PHARMACY -> LineIcon.Glyph.MEDICINE
        ShopCategory.HARDWARE -> LineIcon.Glyph.HAMMER
        ShopCategory.STATIONERY -> LineIcon.Glyph.PEN
        ShopCategory.ELECTRONICS -> LineIcon.Glyph.BULB
        ShopCategory.GENERAL -> LineIcon.Glyph.SHOP
    }

    // ---- step 2: language --------------------------------------------------

    /**
     * Two painted doors, one per language. Each is captioned in its own
     * language, so whichever one a person can read is the one meant for them.
     */
    private fun languageStep(t: Strings.Bundle) {
        val o = t.onboarding
        root.addView(title(o.languageTitle, 28f))
        root.addView(body(o.languageSubtitle).apply { setPadding(0, dp(8), 0, dp(26)) })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            AppLanguage.entries.forEachIndexed { i, lang ->
                if (i > 0) addView(View(this@OnboardingActivity), LinearLayout.LayoutParams(dp(14), 1))
                addView(doorTile(lang) { finishOnboarding(lang) }, weight())
            }
        })
    }

    private fun doorTile(language: AppLanguage, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(18), dp(14), dp(18))
            val on = language == chosenLanguage
            background = ManuscriptFrame(this@OnboardingActivity, outer = if (on) Theme.accent else Theme.border)
            pressable()
            setOnClickListener { onClick() }

            addView(ArtView(this@OnboardingActivity, R.drawable.art_door), LinearLayout.LayoutParams(-1, dp(170)))
            addView(text(language.displayName, 24f, Theme.ink, Type.display(this@OnboardingActivity)).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(2))
            })
            addView(text(Strings.of(language).main.tagline, 12.5f, Theme.muted, Type.displayItalic(this@OnboardingActivity)).apply {
                gravity = Gravity.CENTER
            })
            addView(gap(12))
            addView(View(this@OnboardingActivity).apply {
                background = LineIcon(this@OnboardingActivity, LineIcon.Glyph.CHEVRON, Theme.accent, sizeDp = 20)
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
        }

    // ----------------------------------------------------------------------

    private fun finishOnboarding(language: AppLanguage) {
        chosenLanguage = language
        val category = chosenCategory ?: ShopCategory.GENERAL

        ShopSettings.setCategory(this, category)
        ShopSettings.setLanguage(this, language)
        scope.launch { ShopSettings.persist(this@OnboardingActivity) }

        startActivity(
            android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.vaani_fade_in, R.anim.vaani_fade_out)
        finish()
    }

    private companion object {
        const val TOTAL_STEPS = 2
    }
}
