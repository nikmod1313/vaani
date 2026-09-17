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
}

_STATIONERY = {
    "pen", "पेन", "kalam", "कलम", "pencil", "पेंसिल", "eraser", "rubber",
    "copy", "कॉपी", "notebook", "register", "रजिस्टर", "book", "किताब",
    "kitab", "file", "फाइल", "folder", "paper", "कागज", "kagaz",
    "chart", "sheet", "marker", "मार्कर", "highlighter", "sketch",
    "colour", "color", "crayon", "scale", "ruler", "sharpener",
    "stapler", "pin", "clip", "tape", "gum", "glue", "fevicol",
    "envelope", "lifafa", "लिफाफा", "card", "calculator", "bag", "bastaa",
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

_MODIFIERS: dict[Category, set[str]] = {
    Category.APPAREL: _COLOURS | _SIZES,
    Category.HARDWARE: _COLOURS | _SIZES,
    Category.ELECTRONICS: _COLOURS | _SIZES,
    Category.STATIONERY: _COLOURS,
    Category.KIRANA: set(),
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
