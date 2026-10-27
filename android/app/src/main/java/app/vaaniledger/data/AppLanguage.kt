package app.vaaniledger.data

/**
 * The two interface languages Vaani speaks.
 *
 * HI is Hindi in Devanagari -- "हिंदी" on the picker means the app reads in
 * Devanagari, with the everyday shop words kept as shopkeepers say them
 * (एंट्री, ऑर्डर, UPI). EN is full English.
 *
 * Kept to two options on purpose. `ShopEntity.language` already carries an
 * IETF tag ("hi-IN") for `/summary/narrate`, which supports a third
 * (`mr-IN`, Marathi) that the interface does not yet have strings for --
 * adding it here would silently promise a UI that is not written.
 */
enum class AppLanguage(val wire: String, val displayName: String) {
    HI("hi-IN", "हिंदी"),
    EN("en-IN", "English");

    /** Dates and month names in this language: "28 अक्तू॰" or "28 Oct". */
    val locale: java.util.Locale
        get() = if (this == EN) java.util.Locale("en", "IN") else java.util.Locale("hi", "IN")

    companion object {
        fun from(value: String?) =
            entries.firstOrNull { it.wire == value } ?: HI
    }
}

/** A shop category's name in the interface language, as "name / gloss". */
fun ShopCategory.labelIn(language: AppLanguage): String =
    if (language == AppLanguage.EN) label else when (this) {
        ShopCategory.KIRANA -> "किराना / जनरल स्टोर"
        ShopCategory.APPAREL -> "कपड़े / वस्त्र"
        ShopCategory.PHARMACY -> "दवाई / मेडिकल"
        ShopCategory.HARDWARE -> "हार्डवेयर / बिल्डिंग"
        ShopCategory.STATIONERY -> "स्टेशनरी / किताबें"
        ShopCategory.ELECTRONICS -> "इलेक्ट्रॉनिक्स / मोबाइल"
        ShopCategory.GENERAL -> "दूसरा / अन्य"
    }
