package app.vaaniledger

import app.vaaniledger.ui.Theme

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.vaaniledger.data.AppLanguage
import app.vaaniledger.data.ShopCategory
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
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
 */
class OnboardingActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var step = 0
    private var chosenCategory: ShopCategory? = null
    // Defaults the WELCOME screen to Hinglish, since that is the language it
    // is written in before anyone has chosen anything.
    private var chosenLanguage: AppLanguage = AppLanguage.HI

    private val ink = Theme.ink
    private val muted = Theme.muted
    private val indigo = Theme.primary
    private val paper = Theme.paper
    private val line = Theme.border

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------------------

    private fun render() {
        root.removeAllViews()
        val bundle = Strings.of(chosenLanguage)
        val o = bundle.onboarding

        if (step > 0) {
            root.addView(TextView(this).apply {
                text = o.stepOf(step, TOTAL_STEPS)
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(6))
            })
            root.addView(dots())
            root.addView(View(this), LinearLayout.LayoutParams(0, dp(20)))
        }

        when (step) {
            0 -> welcome(bundle)
            1 -> categoryStep(bundle)
            2 -> languageStep(bundle)
        }
    }

    private fun dots(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (i in 1 until TOTAL_STEPS + 1) {
            addView(View(this@OnboardingActivity).apply {
                background = GradientDrawable().apply {
                    setColor(if (i <= step) indigo else line)
                    cornerRadius = dp(3).toFloat()
                }
            }, LinearLayout.LayoutParams(dp(28), dp(5)).apply {
                marginEnd = dp(6)
            })
        }
    }

    // ---- step 0: welcome --------------------------------------------------

    private fun welcome(t: Strings.Bundle) {
        val o = t.onboarding
        root.addView(TextView(this).apply {
            text = "👋"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
        })
        root.addView(TextView(this).apply {
            text = o.welcomeTitle
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = o.welcomeSubtitle
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, 0, dp(36))
        })
        root.addView(primaryButton(o.welcomeStart) { step = 1; render() })
    }

    // ---- step 1: category --------------------------------------------------

    private fun categoryStep(t: Strings.Bundle) {
        val o = t.onboarding
        root.addView(TextView(this).apply {
            text = o.categoryTitle
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = o.categorySubtitle
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(22))
        })

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ShopCategory.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
            }
            pair.forEach { cat ->
                row.addView(
                    categoryTile(cat) { chosenCategory = cat; step = 2; render() },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = if (cat == pair.first()) dp(10) else 0 },
                )
            }
            grid.addView(row)
        }
        root.addView(grid)

        root.addView(TextView(this).apply {
            text = "← ${o.back}"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(18), dp(4), dp(4))
            isClickable = true
            setOnClickListener { step = 0; render() }
        })
    }

    private fun categoryTile(category: ShopCategory, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), line)
            }
            isClickable = true
            setOnClickListener { onClick() }

            addView(TextView(this@OnboardingActivity).apply {
                text = emojiFor(category)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@OnboardingActivity).apply {
                text = category.label
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        }

    private fun emojiFor(category: ShopCategory): String = when (category) {
        ShopCategory.KIRANA -> "🛒"
        ShopCategory.APPAREL -> "👕"
        ShopCategory.PHARMACY -> "💊"
        ShopCategory.HARDWARE -> "🔧"
        ShopCategory.STATIONERY -> "📒"
        ShopCategory.ELECTRONICS -> "🔌"
        ShopCategory.GENERAL -> "🏪"
    }

    // ---- step 2: language --------------------------------------------------

    private fun languageStep(t: Strings.Bundle) {
        val o = t.onboarding
        root.addView(TextView(this).apply {
            text = o.languageTitle
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = o.languageSubtitle
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(22))
        })

        AppLanguage.entries.forEach { lang ->
            root.addView(
                languageTile(lang) { finishOnboarding(lang) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) },
            )
        }

        root.addView(TextView(this).apply {
            text = "← ${o.back}"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(18), dp(4), dp(4))
            isClickable = true
            setOnClickListener { step = 1; render() }
        })
    }

    private fun languageTile(language: AppLanguage, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), line)
            }
            isClickable = true
            setOnClickListener { onClick() }

            addView(TextView(this@OnboardingActivity).apply {
                text = language.displayName
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(this@OnboardingActivity).apply {
                text = "›"
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            })
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
        finish()
    }

    private fun primaryButton(title: String, onClick: () -> Unit): View =
        TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(17), dp(16), dp(17))
            background = GradientDrawable().apply {
                setColor(indigo)
                cornerRadius = dp(12).toFloat()
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TOTAL_STEPS = 2
    }
}
