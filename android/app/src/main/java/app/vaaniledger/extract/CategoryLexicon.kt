package app.vaaniledger.extract

import app.vaaniledger.data.ShopCategory

/**
 * Per-category item vocabularies, on-device.
 *
 * A Kotlin port of `backend/app/extraction/categories.py`. Same two rules as
 * the Python original, and both were learned the hard way:
 *
 * **Categories are additive, never exclusive.** Every shop gets the GENERAL
 * floor on top of its own list, because a kirana store still sells a bucket
 * and a clothes shop still sells a carry bag.
 *
 * **GENERAL is the OPEN vocabulary, not the empty one.** It means nobody has
 * said what this shop sells, and the honest answer to that is to recognise
 * anything any category knows. The Python copy shipped with an empty set
 * here once, and a real order reached a real khata as "kurti 3" instead of
 * "blue kurti 3" -- see [modifiersFor].
 */
object CategoryLexicon {

    private val KIRANA = setOf(
        "chawal", "rice", "चावल", "atta", "aata", "flour", "आटा",
        "chai", "chay", "tea", "चाय", "cheeni", "chini", "sugar", "चीनी",
        "tel", "oil", "तेल", "dal", "daal", "दाल", "namak", "salt", "नमक",
        "doodh", "dudh", "milk", "दूध", "dahi", "curd", "दही",
        "sabun", "soap", "साबुन", "detergent", "surf", "डिटर्जेंट",
        "biscuit", "biskut", "बिस्कुट", "maggi", "मैगी", "noodles",
        "masala", "मसाला", "haldi", "हल्दी", "mirch", "मिर्च",
        "aalu", "aloo", "aalo", "alu", "potato", "आलू", "pyaz", "onion", "प्याज",
        "tamatar", "tomato", "टमाटर", "anda", "egg", "अंडा",
        "bread", "ब्रेड", "paneer", "पनीर", "ghee", "घी",
        "shampoo", "शैम्पू", "colgate", "toothpaste", "manjan",
        "agarbatti", "अगरबत्ती", "matchbox", "mombatti",
    )

    private val APPAREL = setOf(
        "kurti", "kurta", "कुर्ती", "कुर्ता", "saree", "sari", "साड़ी",
        "salwar", "salwaar", "सलवार", "kameez", "कमीज़", "dupatta", "दुपट्टा",
        "lehenga", "लहंगा", "shirt", "शर्ट", "tshirt", "t-shirt", "टीशर्ट",
        "pant", "pants", "trouser", "trousers", "पैंट", "jeans", "जींस",
        "saya", "petticoat", "blouse", "ब्लाउज", "frock", "फ्रॉक",
        "jacket", "जैकेट", "sweater", "स्वेटर", "shawl", "शॉल",
        "chunni", "चुन्नी", "nighty", "naitee", "legging", "leggings",
        "banyan", "baniyan", "बनियान", "vest", "underwear", "chaddi",
        "socks", "mauze", "मोज़े", "topi", "cap", "टोपी",
        "saada", "dhoti", "धोती", "lungi", "लुंगी", "pyjama", "pajama",
        "churidar", "चूड़ीदार", "sherwani", "शेरवानी", "gown", "गाउन",
        "towel", "tauliya", "तौलिया", "bedsheet", "chadar", "चादर",
        "rumal", "handkerchief", "scarf", "stole",
    )

    private val PHARMACY = setOf(
        "tablet", "tablets", "टैबलेट", "goli", "गोली", "capsule", "कैप्सूल",
        "syrup", "सिरप", "injection", "इंजेक्शन", "drop", "drops", "ड्रॉप",
        "ointment", "cream", "क्रीम", "malham", "मलहम", "bandage", "पट्टी",
        "patti", "cotton", "rui", "रुई", "mask", "मास्क", "sanitizer",
        "dawai", "dava", "दवाई", "दवा", "medicine", "paracetamol", "crocin",
        "dolo", "combiflam", "disprin", "eno", "digene", "syringe",
        "thermometer", "glucose", "ors", "inhaler", "sanitary", "napkin",
    )

    private val HARDWARE = setOf(
        "cement", "सीमेंट", "sariya", "sariyaa", "सरिया", "rod", "steel",
        "eent", "ईंट", "brick", "bricks", "ret", "रेत", "sand", "bajri", "बजरी",
        "paint", "पेंट", "brush", "कूची", "keel", "कील", "nail", "nails",
        "screw", "पेंच", "pench", "hammer", "hathoda", "हथौड़ा",
        "pipe", "पाइप", "tap", "nal", "नल", "wire", "तार", "taar",
        "switch", "स्विच", "bulb", "बल्ब", "holder", "board",
        "plywood", "hinge", "kabza", "कब्ज़ा", "lock", "tala", "ताला",
        "chain", "rassi", "रस्सी", "rope", "balti", "बाल्टी", "bucket",
        "mug", "मग", "tile", "tiles", "टाइल",
    )

    private val STATIONERY = setOf(
        "pen", "पेन", "kalam", "कलम", "pencil", "पेंसिल", "eraser", "rubber",
        "copy", "कॉपी", "notebook", "register", "रजिस्टर", "book", "किताब",
        "kitab", "file", "फाइल", "folder", "paper", "कागज", "kagaz",
        "chart", "sheet", "marker", "मार्कर", "highlighter", "sketch",
        "colour", "color", "crayon", "scale", "ruler", "sharpener",
        "stapler", "pin", "clip", "tape", "gum", "glue", "fevicol",
        "envelope", "lifafa", "लिफाफा", "card", "calculator", "bastaa",
    )

    private val ELECTRONICS = setOf(
        "mobile", "मोबाइल", "phone", "फोन", "charger", "चार्जर", "cable",
        "earphone", "headphone", "इयरफोन", "battery", "बैटरी", "cell",
        "powerbank", "adapter", "एडाप्टर", "memory", "pendrive",
        "speaker", "स्पीकर", "bluetooth", "cover", "कवर", "case",
        "screenguard", "tempered", "glass", "led", "bulb", "tubelight",
        "fan", "पंखा", "pankha", "iron", "istri", "इस्त्री", "heater",
        "extension", "plug", "socket", "remote", "रिमोट",
        "watch", "घड़ी", "ghadi", "torch", "टॉर्च",
    )

    /** Sold by every kind of shop. Always in scope. */
    private val GENERAL_FLOOR = setOf(
        "bag", "बैग", "thaila", "थैला", "packet", "carry",
        "box", "डिब्बा", "dabba", "bottle", "बोतल", "botal",
        "cup", "glass", "plate", "thali", "थाली", "chammach", "spoon",
    )

    private val BY_CATEGORY: Map<ShopCategory, Set<String>> = mapOf(
        ShopCategory.KIRANA to KIRANA,
        ShopCategory.APPAREL to APPAREL,
        ShopCategory.PHARMACY to PHARMACY,
        ShopCategory.HARDWARE to HARDWARE,
        ShopCategory.STATIONERY to STATIONERY,
        ShopCategory.ELECTRONICS to ELECTRONICS,
        ShopCategory.GENERAL to emptySet(),
    )

    // ----------------------------------------------------------------------
    // Modifiers
    // ----------------------------------------------------------------------
    //
    // Adjectives that belong to the item they precede. Kept per category
    // because "blue" matters to a clothes shop and a shared list would drag
    // every shop's false-positive rate up for no gain.

    private val COLOURS = setOf(
        "blue", "नीला", "neela", "red", "लाल", "laal", "green", "हरा", "hara",
        "black", "काला", "kala", "kaala", "white", "सफेद", "safed",
        "yellow", "पीला", "peela", "pink", "गुलाबी", "gulabi",
        "orange", "नारंगी", "purple", "बैंगनी", "grey", "gray", "brown",
        "golden", "silver", "cream", "maroon", "navy", "sky",
    )

    private val SIZES = setOf(
        "small", "छोटा", "chota", "chhota", "medium", "large", "बड़ा", "bada",
        "xl", "xxl", "xs", "s", "m", "l", "free", "size",
    )

    private val MODIFIERS: Map<ShopCategory, Set<String>> = mapOf(
        ShopCategory.APPAREL to (COLOURS + SIZES),
        ShopCategory.HARDWARE to (COLOURS + SIZES),
        ShopCategory.ELECTRONICS to (COLOURS + SIZES),
        ShopCategory.STATIONERY to COLOURS,
        ShopCategory.KIRANA to emptySet(),
        ShopCategory.PHARMACY to emptySet(),
        ShopCategory.GENERAL to emptySet(),
    )

    // ----------------------------------------------------------------------
    // Category-specific units
    // ----------------------------------------------------------------------
    //
    // A clothes shop counts in pieces and metres, a chemist in strips and
    // bottles. These merge on top of the shared unit table in RulesEngine.

    private val UNITS_BY_CATEGORY: Map<ShopCategory, Map<String, String>> = mapOf(
        ShopCategory.APPAREL to mapOf(
            "meter" to "m", "metre" to "m", "mtr" to "m", "मीटर" to "m",
            "set" to "set", "सेट" to "set", "pair" to "pair",
            "jodi" to "pair", "जोड़ी" to "pair", "suit" to "set",
        ),
        ShopCategory.PHARMACY to mapOf(
            "strip" to "strip", "स्ट्रिप" to "strip", "tab" to "tab",
            "tabs" to "tab", "vial" to "vial", "tube" to "tube",
            "sachet" to "sachet",
        ),
        ShopCategory.HARDWARE to mapOf(
            "bag" to "bag", "boro" to "sack", "feet" to "ft", "ft" to "ft",
            "foot" to "ft", "inch" to "in", "meter" to "m", "metre" to "m",
            "mtr" to "m", "quintal" to "qtl", "ton" to "ton", "tonne" to "ton",
        ),
        ShopCategory.STATIONERY to mapOf(
            "ream" to "ream", "set" to "set", "pad" to "pad",
        ),
        ShopCategory.ELECTRONICS to mapOf(
            "set" to "set", "meter" to "m", "metre" to "m", "mtr" to "m",
        ),
        ShopCategory.KIRANA to emptyMap(),
        ShopCategory.GENERAL to emptyMap(),
    )

    // ----------------------------------------------------------------------
    // Public accessors
    // ----------------------------------------------------------------------

    /** Every item noun any category knows. */
    fun allKnownItems(): Set<String> =
        BY_CATEGORY.values.fold(GENERAL_FLOOR) { acc, words -> acc + words }

    /** Every adjective any category treats as part of an item name. */
    fun allKnownModifiers(): Set<String> =
        MODIFIERS.values.fold(emptySet()) { acc, words -> acc + words }

    /**
     * The item nouns in scope for a shop. Always includes the general floor.
     *
     * GENERAL returns the open vocabulary rather than the empty one, so a
     * shop that never picked a category still recognises items -- picking one
     * only makes it sharper.
     */
    fun itemsFor(category: ShopCategory): Set<String> =
        if (category == ShopCategory.GENERAL) allKnownItems()
        else (BY_CATEGORY[category] ?: emptySet()) + GENERAL_FLOOR

    /**
     * Adjectives that attach to the item that follows them.
     *
     * GENERAL is open here for the same reason it is for items. Returning an
     * empty set instead dropped the adjective from every entry made before a
     * category was chosen: "3 blue kurti" reached the khata as "kurti 3",
     * and a shopkeeper reading that back cannot tell which kurti.
     */
    fun modifiersFor(category: ShopCategory): Set<String> =
        if (category == ShopCategory.GENERAL) allKnownModifiers()
        else MODIFIERS[category] ?: emptySet()

    /** Category-specific units, merged over the shared table by the caller. */
    fun unitsFor(category: ShopCategory): Map<String, String> =
        UNITS_BY_CATEGORY[category] ?: emptyMap()
}
