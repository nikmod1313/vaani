package app.vaaniledger.data

/**
 * The two interface languages VaaniLedger speaks.
 *
 * HI is Hinglish, not literary Hindi -- the same register the app has used
 * throughout ("Bubble chalu karein", "Chat padhne ki ijaazat"), because that
 * is how the shopkeepers this app is built for actually talk. EN is asked
 * for by name and gets full English, not a second flavour of Hinglish.
 *
 * Kept to two options on purpose. `ShopEntity.language` already carries an
 * IETF tag ("hi-IN") for `/summary/narrate`, which supports a third
 * (`mr-IN`, Marathi) that the interface does not yet have strings for --
 * adding it here would silently promise a UI that is not written.
 */
enum class AppLanguage(val wire: String, val displayName: String) {
    HI("hi-IN", "हिंदी"),
    EN("en-IN", "English");

    companion object {
        fun from(value: String?) =
            entries.firstOrNull { it.wire == value } ?: HI
    }
}
