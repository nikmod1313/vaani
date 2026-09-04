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
    const val NAME = "Sharma Kirana Store"

    private const val PREFS = "vaani_shop"
    private const val KEY_CATEGORY = "category"
    private const val KEY_CHOSEN = "category_chosen"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_LANGUAGE_CHOSEN = "language_chosen"

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
                name = NAME,
                language = language(context).wire,
                category = category(context).wire,
            )
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
