"""
Shop categories and their vocabularies.

The item matcher used to carry one closed list of kirana words, which meant a
clothes shop could not name a single thing it sells. Asking the shopkeeper
what kind of shop this is -- once, at setup -- buys a much better lexicon than
any amount of guessing, and it costs one tap.

Two things are deliberate here:

**Categories are additive, not exclusive.** Every shop gets GENERAL on top of
its own list, because a kirana store still sells a bucket and a clothes shop
still sells a carry bag. Picking a category widens the vocabulary; it never
narrows it below the common floor.

**Modifiers are separate from nouns.** "blue kurti" is one item, not a colour
and a garment. The modifier list exists so the label keeps the adjective the
customer actually used -- a shopkeeper reading "kurti 3pc" in the khata
cannot tell which kurti, and the entry stops being an audit trail.
"""

from __future__ import annotations

from enum import Enum


class Category(str, Enum):
    KIRANA = "kirana"
    APPAREL = "apparel"
    PHARMACY = "pharmacy"
    HARDWARE = "hardware"
    STATIONERY = "stationery"
    ELECTRONICS = "electronics"
    GENERAL = "general"

    @classmethod
    def parse(cls, value: str | None) -> "Category":
        """Never raise. An unknown category degrades to GENERAL."""
        if not value:
            return cls.GENERAL
        try:
            return cls(str(value).strip().lower())
        except ValueError:
            return cls.GENERAL


# Hindi labels, for the category picker on the phone.
LABELS: dict[Category, str] = {
    Category.KIRANA: "Kirana / General Store",
    Category.APPAREL: "Kapde / Clothing",
    Category.PHARMACY: "Dawai / Medical",
    Category.HARDWARE: "Hardware / Building",
    Category.STATIONERY: "Stationery / Books",
    Category.ELECTRONICS: "Electronics / Mobile",
    Category.GENERAL: "Doosra / Other",
}


# --------------------------------------------------------------------------
# Item nouns, per category
# --------------------------------------------------------------------------

_KIRANA = {
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
    # Flours, grains and breakfast. The original list stopped at atta and
    # chawal, so "1 kg besan aur 500 gram sooji" -- an ordinary kirana order
    # -- named nothing the engine knew and was dropped whole.
    "besan", "बेसन", "sooji", "suji", "सूजी", "rava", "rawa", "maida", "मैदा",
    "poha", "पोहा", "daliya", "दलिया", "sabudana", "साबूदाना", "oats", "cornflakes",
    # Pulses by name -- "toor dal" is one item, "toor" its variety (see the
    # kirana modifiers below), but a bare "2 kg rajma" is a pulse on its own.
    "rajma", "राजमा", "chana", "चना", "chhole", "chole", "lobia", "matar", "मटर",
    "moongfali", "mungfali", "मूंगफली", "peanut",
    # Spices
    "jeera", "zeera", "जीरा", "dhaniya", "धनिया", "rai", "राई", "ajwain", "अजवाइन",
    "saunf", "सौंफ", "methi", "मेथी", "elaichi", "ilaichi", "इलायची", "laung", "लौंग",
    "dalchini", "दालचीनी", "kalimirch", "hing", "हींग", "imli", "इमली",
    # Sweeteners, dairy, bakery
    "gud", "गुड़", "shakkar", "शक्कर", "mishri",
    "makhan", "मक्खन", "butter", "cheese", "malai", "मलाई", "lassi", "chaach",
    "rusk", "toast", "bun", "cake", "khari",
    # Packaged food, snacks and drinks -- ordered by brand as often as not
    "namkeen", "नमकीन", "bhujia", "भुजिया", "chips", "lays", "kurkure", "mixture",
    "papad", "पापड़", "achar", "अचार", "pickle", "ketchup", "sauce", "jam",
    "shahad", "honey", "parle", "britannia", "goodday", "oreo", "marie",
    "coke", "pepsi", "sprite", "fanta", "thumsup", "frooti", "maaza", "slice",
    "coldrink", "colddrink", "juice", "soda", "pani", "पानी", "water", "bisleri",
    "coffee", "कॉफी", "horlicks", "bournvita", "complan", "boost",
    "kaju", "काजू", "badam", "बादाम", "kishmish", "किशमिश", "akhrot", "makhana",
    # Household and personal care, again mostly by brand
    "vim", "rin", "lux", "lifebuoy", "dettol", "harpic", "colin", "lizol",
    "phenyl", "nirma", "tide", "ariel", "handwash", "odonil", "goodknight",
    "mortein", "allout", "toothbrush", "razor", "blade", "parachute",
    "vaseline", "powder", "पाउडर", "tissue", "foil",
    # Vegetables and fruit -- the daily order, and none of it was known
    "bhindi", "भिंडी", "lauki", "लौकी", "gobhi", "gobi", "गोभी", "palak", "पालक",
    "adrak", "अदरक", "lahsun", "लहसुन", "nimbu", "नींबू", "kheera", "खीरा",
    "gajar", "गाजर", "baingan", "बैंगन", "karela", "करेला", "tinda", "kaddu",
    "mooli", "मूली", "pudina", "पुदीना", "shimla", "sabzi", "सब्जी", "pyaaz",
    "kela", "केला", "seb", "सेब", "aam", "आम", "angoor", "अंगूर", "santra",
    "संतरा", "papita", "anaar", "अनार", "chiku", "amrood", "tarbooz",
    "chicken", "mutton", "machli",
}

_APPAREL = {
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
}

_PHARMACY = {
    "tablet", "tablets", "टैबलेट", "goli", "गोली", "capsule", "कैप्सूल",
    "syrup", "सिरप", "injection", "इंजेक्शन", "drop", "drops", "ड्रॉप",
    "ointment", "cream", "क्रीम", "malham", "मलहम", "bandage", "पट्टी",
    "patti", "cotton", "rui", "रुई", "mask", "मास्क", "sanitizer",
    "dawai", "dava", "दवाई", "दवा", "medicine", "paracetamol", "crocin",
    "dolo", "combiflam", "disprin", "eno", "digene", "syringe",
    "thermometer", "glucose", "ors", "inhaler", "sanitary", "napkin",
    "pantop", "pan", "azithral", "allegra", "cetirizine", "vicks", "betadine",
    "volini", "moov", "iodex", "savlon", "dettol", "gelusil", "benadryl",
}

_HARDWARE = {
    "cement", "सीमेंट", "sariya", "sariyaa", "सरिया", "rod", "steel",
    "eent", "ईंट", "brick", "bricks", "ret", "रेत", "sand", "bajri", "बजरी",
    "paint", "पेंट", "brush", "कूची", "keel", "कील", "nail", "nails",
    "screw", "पेंच", "pench", "hammer", "hathoda", "हथौड़ा",
    "pipe", "पाइप", "tap", "nal", "नल", "wire", "तार", "taar",
    "switch", "स्विच", "bulb", "बल्ब", "holder", "board",
    "plywood", "board", "hinge", "kabza", "कब्ज़ा", "lock", "tala", "ताला",
    "chain", "rassi", "रस्सी", "rope", "balti", "बाल्टी", "bucket",
    "mug", "मग", "tile", "tiles", "टाइल",
    "putty", "पुट्टी", "primer", "distemper", "thinner", "fevicol", "sealant",
    "nut", "bolt", "washer", "valve", "elbow", "socket",
}

_STATIONERY = {
    "pen", "पेन", "kalam", "कलम", "pencil", "पेंसिल", "eraser", "rubber",
    "copy", "कॉपी", "notebook", "register", "रजिस्टर", "book", "किताब",
    "kitab", "file", "फाइल", "folder", "paper", "कागज", "kagaz",
    "chart", "sheet", "marker", "मार्कर", "highlighter", "sketch",
    "colour", "color", "crayon", "scale", "ruler", "sharpener",
    "stapler", "pin", "clip", "tape", "gum", "glue", "fevicol",
    "envelope", "lifafa", "लिफाफा", "card", "calculator", "bag", "bastaa",
    "refill", "compass", "diary",
}

_ELECTRONICS = {
    "mobile", "मोबाइल", "phone", "फोन", "charger", "चार्जर", "cable",
    "earphone", "headphone", "इयरफोन", "battery", "बैटरी", "cell",
    "powerbank", "adapter", "एडाप्टर", "memory", "card", "pendrive",
    "speaker", "स्पीकर", "bluetooth", "cover", "कवर", "case",
    "screenguard", "tempered", "glass", "led", "bulb", "tubelight",
    "fan", "पंखा", "pankha", "iron", "istri", "इस्त्री", "heater",
    "extension", "board", "plug", "socket", "remote", "रिमोट",
    "watch", "घड़ी", "ghadi", "torch", "टॉर्च",
}

# Sold by every kind of shop. Always in scope.
_GENERAL = {
    "bag", "बैग", "thaila", "थैला", "packet", "carry",
    "box", "डिब्बा", "dabba", "bottle", "बोतल", "botal",
    "cup", "glass", "plate", "thali", "थाली", "chammach", "spoon",
}


_BY_CATEGORY: dict[Category, set[str]] = {
    Category.KIRANA: _KIRANA,
    Category.APPAREL: _APPAREL,
    Category.PHARMACY: _PHARMACY,
    Category.HARDWARE: _HARDWARE,
    Category.STATIONERY: _STATIONERY,
    Category.ELECTRONICS: _ELECTRONICS,
    Category.GENERAL: set(),
}


# --------------------------------------------------------------------------
# Modifiers
# --------------------------------------------------------------------------
#
# Adjectives that belong to the item they precede. Kept per category because
# "blue" matters to a clothes shop and "500ml" matters to a chemist, and a
# shared list would drag every shop's false-positive rate up for no gain.

_COLOURS = {
    "blue", "नीला", "neela", "red", "लाल", "laal", "green", "हरा", "hara",
    "black", "काला", "kala", "kaala", "white", "सफेद", "safed",
    "yellow", "पीला", "peela", "pink", "गुलाबी", "gulabi",
    "orange", "नारंगी", "purple", "बैंगनी", "grey", "gray", "brown",
    "golden", "silver", "cream", "maroon", "navy", "sky",
}

_SIZES = {
    "small", "छोटा", "chota", "chhota", "medium", "large", "बड़ा", "bada",
    "xl", "xxl", "xs", "s", "m", "l", "free", "size",
}

# Grocery varieties and brands. "toor dal" and "moong dal" are two different
# lines; without these they both became "dal" and the threader merged them
# into one, dropping a kilo of somebody's order. Some are nouns as well
# ("chana", "amul") -- extract_items reads those as a modifier only when an
# item follows them.
_GROCERY = {
    "toor", "tuar", "arhar", "moong", "mung", "masoor", "urad", "chana",
    "basmati", "sona", "kolam", "sarson", "sarso", "mustard", "refined",
    "groundnut", "desi", "organic", "amul", "tata", "fortune", "aashirvaad",
    "patanjali", "mother", "britannia", "garam", "kali", "hari", "lal",
    "brown", "white", "fresh", "toned", "full", "double",
    # Brands said in front of the thing: "2 dettol soap", "lux sabun". Each
    # is also an item on its own ("1 dettol").
    "dettol", "lux", "lifebuoy", "vim", "rin", "surf", "colgate", "parle",
    "harpic", "lizol", "nirma", "tide", "ariel", "dabur", "haldiram", "bikaji",
    "nestle", "everest", "mdh",
}

_MODIFIERS: dict[Category, set[str]] = {
    Category.APPAREL: _COLOURS | _SIZES,
    Category.HARDWARE: _COLOURS | _SIZES,
    Category.ELECTRONICS: _COLOURS | _SIZES,
    Category.STATIONERY: _COLOURS | {"geometry", "drawing", "practical", "long", "single", "double"},
    Category.KIRANA: _GROCERY,
    Category.PHARMACY: set(),
    Category.GENERAL: set(),
}


# --------------------------------------------------------------------------
# Category-specific units
# --------------------------------------------------------------------------
#
# A clothes shop counts in pieces and metres, a chemist in strips and bottles.
# These merge on top of the shared unit table in rules.py.

_UNITS_BY_CATEGORY: dict[Category, dict[str, str]] = {
    Category.APPAREL: {
        "meter": "m", "metre": "m", "mtr": "m", "मीटर": "m",
        "set": "set", "सेट": "set", "pair": "pair", "jodi": "pair", "जोड़ी": "pair",
        "suit": "set",
    },
    Category.PHARMACY: {
        "strip": "strip", "स्ट्रिप": "strip", "tab": "tab", "tabs": "tab",
        "patta": "strip", "patte": "strip", "पत्ता": "strip", "strips": "strip",
        "vial": "vial", "tube": "tube", "sachet": "sachet",
    },
    Category.HARDWARE: {
        "bag": "bag", "boro": "sack", "feet": "ft", "ft": "ft",
        "foot": "ft", "inch": "in", "meter": "m", "metre": "m", "mtr": "m",
        "quintal": "qtl", "ton": "ton", "tonne": "ton",
    },
    Category.STATIONERY: {
        "ream": "ream", "set": "set", "pad": "pad",
    },
    Category.ELECTRONICS: {
        "set": "set", "meter": "m", "metre": "m", "mtr": "m",
    },
    Category.KIRANA: {},
    Category.GENERAL: {},
}


# --------------------------------------------------------------------------
# Public accessors
# --------------------------------------------------------------------------

def items_for(category: Category | str | None) -> set[str]:
    """
    The item nouns in scope for a shop. Always includes the general floor.

    GENERAL is the open-vocabulary case rather than the empty one. It means
    "nobody has told us what this shop sells", and the honest response to
    that is to recognise anything any category knows -- not to recognise
    nothing. It is also what an un-migrated shop and every existing caller
    get by passing None, so the lexicon never silently shrinks under code
    that predates categories.
    """
    cat = category if isinstance(category, Category) else Category.parse(category)
    if cat is Category.GENERAL:
        return all_known_items()
    return _BY_CATEGORY.get(cat, set()) | _GENERAL


def modifiers_for(category: Category | str | None) -> set[str]:
    """
    Adjectives that attach to the item that follows them.

    GENERAL is the open case here for the same reason it is for items: it
    means nobody has said what the shop sells, not that the shop sells
    nothing describable. Returning an empty set instead dropped the adjective
    from every entry made before a category was picked -- "3 blue kurti"
    reached the khata as "kurti 3", which is exactly the loss the modifier
    field exists to prevent. A shopkeeper reading that line back cannot tell
    which kurti, and the entry stops being an audit trail.
    """
    cat = category if isinstance(category, Category) else Category.parse(category)
    if cat is Category.GENERAL:
        return all_known_modifiers()
    return _MODIFIERS.get(cat, set())


def units_for(category: Category | str | None) -> dict[str, str]:
    """Category-specific units, to be merged over the shared table."""
    cat = category if isinstance(category, Category) else Category.parse(category)
    return _UNITS_BY_CATEGORY.get(cat, {})


def all_known_modifiers() -> set[str]:
    """Every adjective any category treats as part of an item name."""
    out: set[str] = set()
    for words in _MODIFIERS.values():
        out |= words
    return out


# --------------------------------------------------------------------------
# One product, many words
# --------------------------------------------------------------------------
#
# The same thing arrives in English, in Hinglish spelled three ways, and in
# Devanagari. A thread that says "chini hai kya?" and gets "haan sugar 45
# kilo" is about ONE item; treated as two, the customer's "1 kilo de do"
# could not say which, and the order lost its quantity and its price. The
# first word of each group is the canonical key; the customer's own word is
# still what the khata shows.
_SYNONYMS: list[tuple[str, ...]] = [
    ("cheeni", "chini", "sugar", "shakkar", "चीनी", "शक्कर"),
    ("aata", "atta", "flour", "आटा"),
    ("chawal", "rice", "चावल"),
    ("doodh", "dudh", "milk", "दूध"),
    ("dahi", "curd", "दही"),
    ("pyaz", "pyaaz", "onion", "प्याज"),
    ("aloo", "aalu", "aalo", "alu", "potato", "आलू"),
    ("tamatar", "tomato", "टमाटर"),
    ("anda", "egg", "अंडा"),
    ("kela", "banana", "केला"),
    ("seb", "apple", "सेब"),
    ("tel", "oil", "तेल"),
    ("chai", "chay", "tea", "चाय"),
    ("namak", "salt", "नमक"),
    ("sabun", "soap", "साबुन"),
    ("dal", "daal", "दाल"),
    ("makhan", "butter", "मक्खन"),
    ("pani", "water", "पानी"),
    ("gobhi", "gobi", "गोभी"),
    ("tala", "lock", "ताला"),
    ("kabza", "hinge", "कब्ज़ा"),
    ("dawai", "dava", "medicine", "दवाई", "दवा"),
    ("goli", "tablet", "गोली", "टैबलेट"),
    ("kalam", "pen", "पेन", "कलम"),
    ("copy", "notebook", "कॉपी"),
    ("coldrink", "colddrink"),
]
_CANONICAL = {w: group[0] for group in _SYNONYMS for w in group}


def canonical(word: str | None) -> str:
    """The product key for an item noun or modifier: the same for every spelling of it."""
    if not word:
        return ""
    return _CANONICAL.get(word, word)


# Garments named by two nouns: "salwar kameez" is one suit, not a salwar and
# a kameez. Read as a modifier and a noun, so the label keeps both words.
_COMPOUNDS: set[tuple[str, str]] = {
    ("salwar", "kameez"), ("salwaar", "kameez"), ("kurta", "pajama"),
    ("kurta", "pyjama"), ("kurti", "pant"), ("kurti", "legging"),
    ("saree", "blouse"), ("shirt", "pant"), ("salwar", "suit"),
}


def is_compound(first: str, second: str) -> bool:
    return (first, second) in _COMPOUNDS


def pharmacy_items() -> set[str]:
    """The chemist's nouns -- the ones whose trailing number is a strength."""
    return set(_PHARMACY)


def all_known_items() -> set[str]:
    """
    Every noun any category knows.

    Used by the conversation threader, which has to recognise an item named
    in an EARLIER turn before it knows which shop is asking.
    """
    out: set[str] = set(_GENERAL)
    for words in _BY_CATEGORY.values():
        out |= words
    return out
