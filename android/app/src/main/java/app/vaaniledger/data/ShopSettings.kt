package app.vaaniledger.data

import android.content.Context

/**
 * The shop's own identity and settings.
 *
 * These were private constants inside OverlayService, which was fine while
 * the overlay was the only thing that saved an entry. Three entry points now
 * write to the same ledger -- the bubble, the live chat screening and the
 * export import -- and a shop id that differs between them would split one
 * khata into three that each look half empty.
 *
 * The category is mirrored into SharedPreferences because the UI and the
 * capture path need it synchronously, on the main thread, before any Room
 * read could return. Room stays the source of truth: [apply] writes both, and
 * the sync worker sends the Room copy.
 */
object ShopSettings {

    const val ID = "sharma-kirana"
    /**
     * Only a fallback: onboarding asks every shop for its own name, so this
     * shows only if that step was somehow skipped. Neutral on purpose -- a
     * real person's shop name here would appear on a stranger's reminders.
     */
    const val NAME = "Meri Dukaan"

    private const val PREFS = "vaani_shop"
    private const val KEY_CATEGORY = "category"
    private const val KEY_CHOSEN = "category_chosen"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_LANGUAGE_CHOSEN = "language_chosen"
    private const val KEY_NAME = "shop_name"
    private const val KEY_UPI = "upi_id"

    /**
     * The shop's name as the shopkeeper wants it written -- on every page
     * header, in every reminder a customer reads. [NAME] is only the default
     * until they set their own; [ID] never changes, because the khata and
     * the server are keyed on it.
     */
    fun name(context: Context): String =
        prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: NAME

    fun setName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_NAME, name.trim()).apply()
    }

    /**
     * The shop's UPI ID, so a payment reminder can carry a pay link the
     * customer taps once. Null until set; a reminder without one still
     * goes, just without the link.
     */
    fun upiId(context: Context): String? =
        prefs(context).getString(KEY_UPI, null)?.takeIf { it.isNotBlank() }

    fun setUpiId(context: Context, vpa: String?) {
        prefs(context).edit().putString(KEY_UPI, vpa?.trim()?.lowercase()).apply()
    }

    /** name@bank -- the shape every UPI ID has. Checked so a typo fails here, not on a customer's phone. */
    fun isValidUpiId(vpa: String): Boolean = Regex("""^[a-z0-9.\-_]{2,256}@[a-z][a-z0-9]{1,64}$""").matches(vpa.trim().lowercase())

    fun category(context: Context): ShopCategory =
        ShopCategory.from(prefs(context).getString(KEY_CATEGORY, null))

    /**
     * Has the shopkeeper actually chosen, or are we still on the default?
     *
     * Worth distinguishing: "general" as a deliberate answer and "general"
     * because nobody was asked look identical in the stored value, and only
     * the second one should make the setup screen keep prompting.
     */
    fun hasChosenCategory(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHOSEN, false)

    /** Record the choice locally. Room and the server follow via [persist]. */
    fun setCategory(context: Context, category: ShopCategory) {
        prefs(context).edit()
            .putString(KEY_CATEGORY, category.wire)
            .putBoolean(KEY_CHOSEN, true)
            .apply()
    }

    fun language(context: Context): AppLanguage =
        AppLanguage.from(prefs(context).getString(KEY_LANGUAGE, null))

    fun hasChosenLanguage(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LANGUAGE_CHOSEN, false)

    fun setLanguage(context: Context, language: AppLanguage) {
        prefs(context).edit()
            .putString(KEY_LANGUAGE, language.wire)
            .putBoolean(KEY_LANGUAGE_CHOSEN, true)
            .apply()
    }

    /**
     * Both questions answered, once, ever.
     *
     * This is the gate the app checks on every cold start. Before this
     * shipped, the category picker asked on every visit to the setup
     * screen -- indistinguishable from a permission that had not been
     * granted yet, and exactly the confusion the screenshot showed. Setup
     * is a one-time conversation now: [OnboardingActivity] runs until this
     * is true, and nothing after that asks again unless the shopkeeper
     * opens settings on purpose.
     */
    fun hasCompletedOnboarding(context: Context): Boolean =
        hasChosenCategory(context) && hasChosenLanguage(context)

    /** Write the shop row, so Room and the next sync agree with the prefs. */
    suspend fun persist(context: Context) {
        LedgerDatabase.get(context).shops().upsert(
            ShopEntity(
                id = ID,
                name = name(context),
                language = language(context).wire,
                category = category(context).wire,
            )
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
