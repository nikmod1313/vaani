"""
The deterministic extraction layer.

Runs before any model call and resolves most entries on its own: the amount,
whether it was cash / UPI / udhaar, which items and how many, and whether the
message is a sale or a payment coming back.

Everything here is rules and regex. When it is confident, no model is needed;
when it is not, it hands a partial result up to the LLM layer with the gaps
marked. Cheap, offline, and deterministic -- which matters because this is the
layer deciding how much money goes into someone's khata.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum

from .categories import Category, is_compound, items_for, modifiers_for, pharmacy_items, units_for
from .numbers import BOUNDARY, find_numbers, is_number_token, normalise, tokenize


class PaymentMode(str, Enum):
    CASH = "cash"
    UPI = "upi"
    UDHAAR = "udhaar"
    UNKNOWN = "unknown"


class Direction(str, Enum):
    SALE = "sale"            # goods out, money owed or received
    PAYMENT = "payment"      # customer settling an existing balance
    UNKNOWN = "unknown"


# --------------------------------------------------------------------------
# Lexicons
# --------------------------------------------------------------------------

_MODE_WORDS: dict[str, PaymentMode] = {
    # Cash
    "nakad": PaymentMode.CASH, "nagad": PaymentMode.CASH,
    "cash": PaymentMode.CASH, "rokad": PaymentMode.CASH,
    "नकद": PaymentMode.CASH, "नगद": PaymentMode.CASH,
    # UPI and its brand names -- shopkeepers say the app, not the protocol
    "upi": PaymentMode.UPI, "phonepe": PaymentMode.UPI,
    "gpay": PaymentMode.UPI, "googlepay": PaymentMode.UPI,
    "paytm": PaymentMode.UPI, "bhim": PaymentMode.UPI,
    "online": PaymentMode.UPI, "scan": PaymentMode.UPI,
    "qr": PaymentMode.UPI, "transfer": PaymentMode.UPI,
    "यूपीआई": PaymentMode.UPI, "ऑनलाइन": PaymentMode.UPI,
    # Credit
    "udhaar": PaymentMode.UDHAAR, "udhar": PaymentMode.UDHAAR,
    "udhaari": PaymentMode.UDHAAR, "baaki": PaymentMode.UDHAAR,
    "baki": PaymentMode.UDHAAR, "credit": PaymentMode.UDHAAR,
    "khaate": PaymentMode.UDHAAR, "khate": PaymentMode.UDHAAR,
    "उधार": PaymentMode.UDHAAR, "बाकी": PaymentMode.UDHAAR,
    # Cash on delivery is how an online-style order says "cash".
    "cod": PaymentMode.CASH,
}

# Devanagari-safe word boundaries.
#
# `\b` is defined against `\w`, and Python excludes Devanagari vowel signs
# (matras such as e, aa, i) from `\w` because they are nonspacing marks. So a
# trailing `\b` NEVER matches after a word ending in a matra -- which is most
# Hindi words. Every `\b...\b` Devanagari pattern here was silently dead
# code, matching nothing, for as long as it had been there.
#
# Whitespace boundaries mean what we actually want and work in both scripts.
DEVA_START = r"(?<!\S)"
DEVA_END = r"(?!\S)"

# Phrases meaning "write it down" -> credit, even without the word udhaar.
_CREDIT_PHRASES = [
    r"\blikh\s+(?:lena|lo|dena|do|dijiye|lijiye|lijie)\b",
    # "khate me" is written at least as often as "khate mein".
    r"\bkhaate?\s+m(?:ein|e|ai|ain)\b",
    r"\b(?:hisaa?b|account|khaate?)\s+m(?:ein|e)\s+(?:jod|likh|daal|dal|add)\w*",
    r"\bjod\s+(?:do|dena|dijiye|lena)\b",
    DEVA_START + r"लिख\s+(?:लेना|लो|देना|दो)" + DEVA_END,
    DEVA_START + r"खाते\s+में" + DEVA_END,
]

# Deferred payment is credit, even when nobody says "udhaar".
#
# "Paise kal doonga" and "mahine ke end me hisab kar lenge" are how a regular
# customer actually opens a credit line -- the word udhaar never appears
# because both sides already know that is what this is.
_DEFERRED_PHRASES = [
    r"\bpaise?\b.{0,20}?\b(?:kal|baad|shaam|raat|sunday|somvar)\b",
    r"\b(?:kal|baad\s+me|baad\s+mein|shaam\s+ko|raat\s+ko)\b.{0,15}?\b(?:doonga|dunga|dedunga|de\s+dunga|dungi)\b",
    r"\b(?:doonga|dunga|dungi|dedunga)\b",
    r"\bhisab\b.{0,15}?\b(?:kar\s+lenge|kar\s+lena|baad|end)\b",
    r"\bmahine?\s+ke\s+(?:end|akhir|ant)\b",
    r"\bbaad\s+m[ei]in?\s+(?:de|dunga|doonga|karenge)\w*\b",
    DEVA_START + r"पैसे?\s+कल" + DEVA_END,
    DEVA_START + r"बाद\s+में\s+(?:दूंगा|दे)" + DEVA_END,
]

# Phrases meaning money came back in.
#
# Note `diy[ae]` rather than `diye?`. The latter matches "diy" plus an
# optional "e", so the trailing \b never lands on "diya" -- and "kar diya",
# "de diya" and "bhej diya" are how people actually write it. Every payment
# phrased in the past tense was being read as a sale.
# Future intent, which is NOT a payment.
#
# "I will pay with upi" tells you how a SALE will be settled; reading it as
# money already received would clear a debt nobody has paid, and the
# shopkeeper would never chase it. Checked before the payment phrases, and it
# wins, because the cost of the two mistakes is not symmetric: a missed
# payment gets noticed the next time the khata is read, an invented one does
# not.
_FUTURE_PAYMENT_PHRASES = [
    r"\b(?:i\s+)?will\s+pay\b", r"\bi'?ll\s+pay\b", r"\bgoing\s+to\s+pay\b",
    r"\bpay\s+(?:you\s+)?(?:later|tomorrow|soon|next)\b",
    r"\bpayment\s+(?:later|tomorrow|soon)\b",
    r"\bwill\s+(?:make|send|do)\s+(?:the\s+)?payment\b",
    r"\bpayment\s+will\b",
    r"\bkal\s+(?:de|kar)\w*\b", r"\bbaad\s+m[ei]in?\b",
]

_PAYMENT_PHRASES = [
    # English, PAST tense only -- see _FUTURE_PAYMENT_PHRASES above.
    #
    # These were missing entirely, so "i have completed the payment" left a
    # settled bill sitting in the khata as outstanding. Every wording below
    # came off a real chat.
    r"\bi\s+(?:have\s+)?paid\b",
    r"\bhave\s+paid\b",
    r"\b(?:have\s+)?(?:completed|made|done)\s+the\s+payment\b",
    r"\bpayment\s+(?:is\s+)?(?:done|complete|completed|received|sent|made)\b",
    r"\bdone\s+(?:the\s+)?payment\b",
    r"\b(?:transferred|sent)\s+(?:the\s+)?(?:money|amount|payment|cash)\b",
    r"\bpaid\s+(?:you|it|already|for\s+it)\b",
    r"\bpayment\s+ho\s+gay[ai]\b",
    # explicit payment nouns
    r"\bpayment\b.{0,20}?\b(?:mila|mile|aaya|aa\s+gaya|hua|kiya|kar\s+diy[ae]|kar\s+d(?:i|ii|ia))\b",
    # A bare "paid" -- the whole message -- after a reminder.
    r"^\W*paid\W*$",
    # ...or with its figure: "2000 paid", "₹2000 paid", "paid 2000".
    r"^\W*(?:rs\.?|₹)?\s*\d[\d,]*(?:\.\d+)?\s*(?:rs|rupees|rupaye)?\s+paid\W*$",
    r"^\W*paid\s+(?:rs\.?|₹)?\s*\d[\d,]*(?:\.\d+)?\W*$",
    r"\bpaise?\b.{0,15}?\b(?:mil|aa|de|bhej|diy)\w*\b",
    r"\bmil\s+gaye?\b",
    # settling up
    r"\bjama\s+kar\w*\b", r"\bchukta\b", r"\bsettle\w*\b",
    r"\b(?:account|khata|hisab|hisaab)\b.{0,15}?\bclear\b",
    r"\bclear\s+kar\s+diy[ae]\b",
    r"\b(?:nakad|nagad|cash|rokad|upi|online)\s+diy[ae]\b",
    # a payment app plus any completed action, in either order -- "gpay kar
    # diya" and "250 bheje hai phonepe se" are the same sentence
    r"\b(?:phonepe|gpay|googlepay|paytm|bhim|upi|online|scan|transfer)\b"
    r".{0,25}?\b(?:kar\s+diy[ae]|bhej\s+diy[ae]|diy[ae]|kiya|kia|bheja|bheje|mila|mile|done|ho\s+gaya)\b",
    r"\b(?:kar\s+diy[ae]|bhej\s+diy[ae]|bheja|bheje|kiya|kia|sent|paid|transferred)\b"
    r".{0,25}?\b(?:phonepe|gpay|googlepay|paytm|bhim|upi|online)\b",
    # Devanagari -- whitespace boundaries, see DEVA_START above
    DEVA_START + r"पैसे?\s*(?:मिल|आ|दे)\w*",
    DEVA_START + r"जमा" + DEVA_END,
    DEVA_START + r"(?:भेज|कर|दे)\s*(?:दिया|दिये|दिए)" + DEVA_END,
    DEVA_START + r"मिल(?:े|गया|गए)" + DEVA_END,
    DEVA_START + r"भेजे" + DEVA_END,
]

# Generic past-tense giving and sending. "bhej diya" is how most payments are
# written -- and also how a complaint about goods is: "kal 3 kg aata bheja
# tha, kharab tha" invented a payment out of a bad bag of flour. So these
# count only when the message is ABOUT money: a money word, a currency mark,
# or a number that is not a measure or a count of something.
_MONEY_GATED_PAYMENT_PHRASES = [
    r"\b(?:de|bhej|kar|jama)\s+diy[ae]\b",
    r"\bbheja\b", r"\bbheje\b",
    # "sent 2250 to your account using upi" -- gated, since "sent the
    # parcel" is not money.
    r"\b(?:sent|transferred)\b",
]
_MONEY_WORDS = re.compile(
    r"\b(?:paise?|paisa|pese|payment|amount|rupaye|rupay|rupees|rs|cash|nakad|nagad|"
    r"upi|online|gpay|phonepe|paytm)\b|₹|रुपये|रुपए|पैसे?"
)

# A payment that SETTLES something names the debt: "kal ke dahi ke paise",
# "pichla hisaab", "mere 500 baaki the". Without one of these, a message that
# lists goods with quantities AND says it was paid is a sale paid on the
# spot -- "3 sabun 120 ka UPI kar diya" -- not a balance being cleared.
_SETTLEMENT_WORDS = re.compile(
    r"\b(?:paise?|paisa|pese|baaki|baki|udhaa?r|hisaa?b|pichl\w*|purana|purane|"
    r"due|balance|khaate?|bakaya)\b|पैसे?|बाकी|उधार|हिसाब"
)

_CASH_PHRASES = re.compile(
    r"\b\d+\s+(?:ka|ke|wala|wale|rupaye\s+ka)\s+note\b|\bnote\s+(?:de|diya|bhej)\w*"
    r"|\bchh?utta\b|\b(?:baaki|baki|bacha|bache|change)\b.{0,12}?\b(?:wapa?s|lauta\w*|return)\b"
)

# Asking for money BACK. "galti se double payment ho gaya, 300 wapas kar do"
# contains "payment ho gaya" and was booked as a Rs 300 payment received --
# the exact opposite of what happened.
_REFUND = re.compile(
    r"\brefund\w*\b|\bwapa?s\s+(?:kar|de|bhej|karo|dena|kijiye)\w*|\b(?:paid|payment)\s+twice\b"
    r"|\b(?:double|do\s+baar|2\s+baar|dobara)\s+(?:payment|pay|gpay|paytm|phonepe|upi|ho\s+gaya)\b"
    r"|\bgalti\s+se\b.{0,30}?\b(?:payment|bhej|pay|gpay|paytm|phonepe)\w*"
    r"|(?<!\S)वापस\s+(?:कर|दे|भेज)"
)

_UNITS: dict[str, str] = {
    "kilo": "kg", "kg": "kg", "kilos": "kg", "kgs": "kg", "kilogram": "kg", "किलो": "kg",
    "gram": "g", "grams": "g", "gm": "g", "gms": "g", "g": "g", "ग्राम": "g",
    "litre": "l", "liter": "l", "litres": "l", "liters": "l", "ltr": "l", "ltrs": "l",
    "lt": "l", "l": "l", "लीटर": "l",
    "ml": "ml",
    "packet": "packet", "pkt": "packet", "pkts": "packet", "pack": "packet",
    "packs": "packet", "packets": "packet", "पैकेट": "packet", "pouch": "packet",
    "tray": "tray", "trays": "tray", "ट्रे": "tray",
    "box": "box", "dabba": "box", "dabbe": "box", "डिब्बा": "box", "डिब्बे": "box",
    "jar": "jar", "tin": "tin", "roll": "roll", "bundle": "bundle",
    "dozen": "dozen", "darjan": "dozen", "दर्जन": "dozen",
    "piece": "pc", "pieces": "pc", "pcs": "pc", "pc": "pc", "nag": "pc", "अदद": "pc",
    "bottle": "bottle", "botal": "bottle", "बोतल": "bottle",
    "bora": "sack", "sack": "sack", "bori": "sack",
    "pav": "pav", "adha": "half",
}

# Common kirana stock. Seeds the item matcher before a shop has history.
_ITEMS: set[str] = {
    "chawal", "rice", "चावल", "atta", "aata", "flour", "आटा",
    "chai", "chay", "tea", "चाय", "cheeni", "chini", "sugar", "चीनी",
    "tel", "oil", "तेल", "dal", "daal", "दाल", "namak", "salt", "नमक",
    "doodh", "dudh", "milk", "दूध", "dahi", "curd", "दही",
    "sabun", "soap", "साबुन", "detergent", "surf", "डिटर्जेंट",
    "biscuit", "biskut", "बिस्कुट", "maggi", "मैगी", "noodles",
    "masala", "मसाला", "haldi", "हल्दी", "mirch", "मिर्च",
    "aalu", "aloo", "potato", "आलू", "pyaz", "onion", "प्याज",
    "tamatar", "tomato", "टमाटर", "anda", "egg", "अंडा",
    "bread", "ब्रेड", "paneer", "पनीर", "ghee", "घी",
    "shampoo", "शैम्पू", "colgate", "toothpaste", "manjan",
    "agarbatti", "अगरबत्ती", "matchbox", "mombatti",
}

# Item words that are also everyday verbs. Same trap as "do" in the number
# parser: "phonepe kar diya" is not a purchase of oil lamps, and "khaate mein
# daal do" is not two kilos of lentils.
#
# These only count as items when a quantity or unit sits next to them, which
# is how a real order names them ("dal 2 kilo", "1 packet dal").
_WEAK_ITEMS: set[str] = {"dal", "daal", "दाल", "aata"}

# Dropped from _ITEMS entirely: "diya" (an oil lamp, but overwhelmingly the
# verb "gave" -- de diya, kar diya, bhej diya). Not worth the false positives
# on every payment message.

_CURRENCY = re.compile(
    r"(?:₹|rs\.?|rupaye|rupees|rupay|rupye|rupya|rupiya|रु\.?|रुपये|रुपए|रूपये)",
    re.IGNORECASE,
)

# Tokens that mark the number before them as MONEY rather than a count.
#
# "ka / ki / ke" is the Hindi possessive: "paune sau ka chai" is seventy-five
# rupees WORTH of tea, not seventy-five teas. Without this the price is
# swallowed as a quantity and the entry saves with no amount at all.
_PRICE_MARKERS = {
    "ka", "ki", "ke", "का", "की", "के",
    "rupaye", "rupees", "rupay", "rupye", "rupya", "rupiya", "rs", "₹", "रुपये", "रुपए",
}

# Joining words. An unattached measure does not survive one: in "2 coke 2
# litre wali aur 1 lays" the "2 litre" describes the coke, and carrying it
# across "aur" put two litres of chips in the khata.
_CONJUNCTIONS = {"aur", "and", "&", "+", "और", "n"}

_POSSESSIVES = {"ka", "ki", "ke", "का", "की", "के"}

# Containers sold as things in their own right, as well as used as measures.
# Deliberately not "packet": "2 packet bhej do" is always a measure of
# whatever was being discussed, never an order for empty packets.
_CONTAINERS = {"box", "dabba", "bottle", "botal", "bag", "बोतल", "डिब्बा"}

# Words meaning the number beside them is a PER-UNIT rate rather than a
# total. "3 kurti 500 each" is fifteen hundred rupees, not five.
#
# Split by how much they actually commit to. "each" and "per" can only ever
# mean a rate. The possessives ka/ki/ke mean a rate OR a total depending on
# what follows -- "500 ka kurti" is a rate, "dhai sau ka udhaar" is the whole
# bill -- so only those need the mode-word guard in _trailing_rate. Applying
# that guard to "each" as well made "3 kurti 500 each udhaar" book Rs 500
# instead of Rs 1500, because the mode word sat where the guard was looking.
_EXPLICIT_RATE_MARKERS = {
    "each", "per", "prati", "प्रति", "rate", "hisab", "हिसाब",
}
_AMBIGUOUS_RATE_MARKERS = {
    "ka", "ke", "ki", "का", "के", "की", "wala", "wali", "वाला", "वाली",
}
_RATE_MARKERS = _EXPLICIT_RATE_MARKERS | _AMBIGUOUS_RATE_MARKERS


# --------------------------------------------------------------------------
# Result types
# --------------------------------------------------------------------------

@dataclass
class LineItem:
    name: str
    quantity: float | None = None
    unit: str | None = None
    #: The adjective the customer actually used -- "blue" in "blue kurti".
    #: Kept because "kurti 3pc" in a khata does not say WHICH kurti, and an
    #: entry nobody can identify later is not an audit trail.
    modifier: str | None = None
    #: Per-unit price, only when the message STATED one ("kurti 500 ka",
    #: "@250", "500 each"). Never inferred from the total -- dividing an
    #: amount by a quantity invents a rate nobody agreed to.
    unit_price: float | None = None

    @property
    def display_name(self) -> str:
        return f"{self.modifier} {self.name}".strip() if self.modifier else self.name

    @property
    def line_total(self) -> float | None:
        """Extended price, only when both sides were actually stated."""
        if self.unit_price is None or self.quantity is None:
            return None
        return round(self.unit_price * self.quantity, 2)

    def label(self) -> str:
        if self.quantity is None:
            return self.display_name
        qty = int(self.quantity) if self.quantity == int(self.quantity) else self.quantity
        return f"{self.display_name} {qty}{self.unit or ''}".strip()

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "modifier": self.modifier,
            "display_name": self.display_name,
            "quantity": self.quantity,
            "unit": self.unit,
            "unit_price": self.unit_price,
            "line_total": self.line_total,
            "label": self.label(),
        }


@dataclass
class RuleResult:
    amount: float | None = None
    amount_source: str | None = None
    mode: PaymentMode = PaymentMode.UNKNOWN
    direction: Direction = Direction.UNKNOWN
    items: list[LineItem] = field(default_factory=list)
    all_numbers: list[tuple[float, str]] = field(default_factory=list)
    # Which fields the rules resolved on their own. Anything missing here is
    # what the LLM layer is asked to fill.
    resolved: set[str] = field(default_factory=set)

    def is_complete(self) -> bool:
        return {"amount", "mode"}.issubset(self.resolved)


# --------------------------------------------------------------------------
# Extraction
# --------------------------------------------------------------------------

def detect_mode(text: str) -> tuple[PaymentMode, str | None]:
    """
    Payment mode plus the word that decided it.

    `is_payment` matters: you cannot pay WITH udhaar. In "wo 250 wala udhaar
    clear kar diya, gpay dekh lena" the word udhaar names the debt being
    settled, while gpay is how it was settled -- so on a payment the
    instrument wins over the debt type.
    """
    norm = normalise(text)

    # Paying with a note is cash, and the "baaki" in "baaki lauta dena" is the
    # change coming back, not a debt. Read as credit, "100 ka note de raha
    # hu, baaki lauta dena" put a paid-for Rs 42 into the udhaar column.
    m = _CASH_PHRASES.search(norm)
    if m:
        return PaymentMode.CASH, m.group(0)

    hits: list[tuple[PaymentMode, str]] = []
    for tok in norm.split():
        clean = tok.strip(".")
        if clean in _MODE_WORDS:
            hits.append((_MODE_WORDS[clean], clean))

    if hits:
        instruments = [h for h in hits if h[0] in (PaymentMode.CASH, PaymentMode.UPI)]
        if instruments and detect_direction(text) is Direction.PAYMENT:
            return instruments[0]
        return hits[0]

    for pattern in _CREDIT_PHRASES + _DEFERRED_PHRASES:
        m = re.search(pattern, norm)
        if m:
            return PaymentMode.UDHAAR, m.group(0)
    return PaymentMode.UNKNOWN, None


def detect_direction(text: str, category: Category | str | None = None) -> Direction:
    """Is this goods going out, or money coming back?"""
    norm = normalise(text)

    # A promise to pay is not a payment. Checked first so no amount of
    # past-tense-looking wording after it can flip the reading.
    promised = any(re.search(p, norm) for p in _FUTURE_PAYMENT_PHRASES)
    # A request for a refund is neither a payment nor a sale.
    if _REFUND.search(norm):
        return Direction.UNKNOWN

    items = extract_items(text, category)

    paid = False
    if not promised:
        paid = any(re.search(p, norm) for p in _PAYMENT_PHRASES)
        if not paid and _about_money(text, norm, items, category):
            paid = any(re.search(p, norm) for p in _MONEY_GATED_PAYMENT_PHRASES)

    if paid:
        # Goods with quantities, paid for in the same breath, are a SALE
        # settled on the spot. Only a message that names a debt is a payment.
        counted = any(it.quantity is not None for it in items)
        if counted and not _SETTLEMENT_WORDS.search(norm):
            return Direction.SALE
        return Direction.PAYMENT
    # Items present means goods moved.
    if items:
        return Direction.SALE
    return Direction.UNKNOWN


def _about_money(
    text: str, norm: str, items: list["LineItem"], category: Category | str | None,
) -> bool:
    """Does this message talk about money, rather than about goods?"""
    if _MONEY_WORDS.search(norm) or _CURRENCY.search(norm):
        return True
    counts = {it.quantity for it in items if it.quantity is not None}
    counts |= _measure_values(text, category)
    return any(value not in counts for value, _ in find_numbers(text))


def _match_item(token: str, lexicon: set[str]) -> str | None:
    """
    Resolve a token to an item noun, allowing an English plural.

    Customers write "blue kurtis" and "2 sarees"; a lexicon holding only the
    singular sees neither, and the item -- the whole subject of the order --
    is silently dropped. Returns the SINGULAR so the khata reads consistently
    however the customer typed it.

    Plurals that are already in a lexicon (jeans, socks, drops, tablets)
    match directly on the first check and are never stripped.
    """
    if token in lexicon:
        return token
    if token.endswith("es") and token[:-2] in lexicon:
        return token[:-2]
    if token.endswith("s") and token[:-1] in lexicon:
        return token[:-1]
    # The Hindi oblique / plural: ande, kele, dabbe, kabze -- and in
    # Devanagari अंडे for अंडा. "ek darjan ande" named no item at all.
    if token.endswith("e") and token[:-1] + "a" in lexicon:
        return token[:-1] + "a"
    if token.endswith("े") and token[:-1] + "ा" in lexicon:
        return token[:-1] + "ा"
    # The Hindi feminine plural and oblique: kurtiyan / kurtiyaan / kurtiyon
    # for kurti, and in Devanagari कुर्तियाँ for कुर्ती. "do kurtiyan kharidi"
    # named no item at all, so the two was left with nothing to count.
    for suffix in ("iyaan", "iyan", "iyon", "iya"):
        if token.endswith(suffix) and token[: -len(suffix)] + "i" in lexicon:
            return token[: -len(suffix)] + "i"
    for suffix in ("ियाँ", "ियां", "ियों"):
        if token.endswith(suffix) and token[: -len(suffix)] + "ी" in lexicon:
            return token[: -len(suffix)] + "ी"
    # ...and the "-en" plural: chappalen, kitaben, किताबें.
    if token.endswith("en") and len(token) > 4 and token[:-2] in lexicon:
        return token[:-2]
    if token.endswith("ें") and token[:-2] in lexicon:
        return token[:-2]
    return None


def normalise_label(label: str) -> str:
    """
    A typed item, put in the "<name> <qty><unit>" shape every label has.

    The edit sheet and the dashboard take items as free text, and "do kurti"
    typed there was stored whole -- a product called "do kurti", quantity
    one. A leading count is lifted off and written the way `label()` writes
    it: "do kurti" -> "kurti 2", "2 kg chawal" -> "chawal 2kg". A digit is
    only taken as a count when a unit or a known item follows it, so a
    product whose name starts with a number ("5 star") is left alone.
    Anything already in the label shape is returned unchanged.
    """
    from .categories import all_known_items
    from .numbers import is_number_token, parse_number_phrase

    raw = " ".join(label.split())
    tokens = raw.split(" ")
    if len(tokens) < 2:
        return raw
    lower = [t.lower().strip(".") for t in tokens]
    j = 0
    while j < len(tokens) - 1 and is_number_token(lower[j]):
        j += 1
    if j == 0 or is_number_token(lower[j]):
        return raw
    qty = parse_number_phrase(lower[:j])
    unit = _UNITS.get(lower[j]) if j < len(tokens) - 1 else None
    rest = tokens[j + 1:] if unit else tokens[j:]
    if qty is None or qty <= 0 or qty >= 1000 or not rest:
        return raw
    worded = not any(ch.isdigit() for ch in "".join(lower[:j]))
    if not (worded or unit or _match_item(rest[-1].lower(), all_known_items())):
        return raw
    shown = int(qty) if qty == int(qty) else qty
    return f"{' '.join(rest)} {shown}{unit or ''}"

def _trailing_rate(
    tokens: list[str],
    start: int,
    lexicon: set[str] | None = None,
) -> float | None:
    """
    A per-unit rate stated after an item: "kurti 500 ka", "2 saree chahiye 800 each".

    How far ahead this looks depends on how much the marker commits to.

    An AMBIGUOUS possessive (ka / ke / ki) is only read as a rate when it sits
    immediately after the item, because the same shape carries the total far
    more often: in "2 kilo chawal, dhai sau ka udhaar" the number is the whole
    bill. Two guards hold that line -- adjacency, and a mode word after the
    marker meaning the number is what is owed.

    An EXPLICIT marker (each / per / prati / rate) cannot mean anything but a
    rate, so it is searched for across the rest of the clause. "2 saree
    chahiye 800 each" puts a verb between the item and its price, and
    requiring adjacency there loses the rate and books Rs 800 instead of
    Rs 1600.

    The scan stops at a clause boundary or at the next item noun, so a rate
    belongs to one item and never leaks onto the one after it.
    """
    from .numbers import _is_verb_not_number, is_number_token, parse_number_phrase

    def number_run(i: int) -> tuple[float | None, int]:
        # "bhej do 500 each": the "do" is "send it", not two. Read as part of
        # the run it made the rate 502 and the bill Rs 1004.
        while i < len(tokens) and _is_verb_not_number(tokens, i):
            i += 1
        j = i
        while j < len(tokens) and is_number_token(tokens[j].strip(".")):
            j += 1
        if j == i:
            return None, i
        return parse_number_phrase(tokens[i:j]), j

    # --- adjacent, any marker -------------------------------------------
    if start < len(tokens) and is_number_token(tokens[start].strip(".")):
        value, j = number_run(start)
        if value is not None and j < len(tokens):
            marker = tokens[j].strip(".")
            after = tokens[j + 1].strip(".") if j + 1 < len(tokens) else ""
            if marker in _RATE_MARKERS and not (
                marker in _AMBIGUOUS_RATE_MARKERS and after in _MODE_WORDS
            ):
                return value if value > 0 else None

    # --- anywhere in the clause, explicit markers only -------------------
    lexicon = lexicon or set()
    i = start
    while i < len(tokens):
        tok = tokens[i].strip(".")
        if tok == BOUNDARY:
            # "2 kurti bhej do, 500 each": the rate sits in the next clause.
            # It can only be this item's when nothing after it names another
            # one -- otherwise it belongs to that item, and the scan stops.
            rest = tokens[i + 1:]
            if any(_match_item(t.strip("."), lexicon) is not None for t in rest):
                break
            i += 1
            continue
        if i > start and _match_item(tok, lexicon) is not None:
            break   # the rate belongs to that item, not this one
        if is_number_token(tok):
            value, j = number_run(i)
            if (
                value is not None
                and value > 0
                and j < len(tokens)
                and tokens[j].strip(".") in _EXPLICIT_RATE_MARKERS
            ):
                return value
            i = max(j, i + 1)
            continue
        i += 1

    return None


def extract_items(
    text: str,
    category: Category | str | None = None,
) -> list[LineItem]:
    """
    Pull item / quantity / unit / rate tuples.

    Walks the token stream tracking the most recent number and unit, so
    "2 kilo chawal" and "chawal 2 kilo" both resolve.

    `category` selects the noun lexicon. A clothes shop cannot name a single
    thing it sells out of a kirana word list, so the shop's own category is
    asked for once at setup and threaded through from there.
    """
    from .numbers import is_number_token, parse_number_phrase

    lexicon = items_for(category)
    modifiers = modifiers_for(category)
    units = {**_UNITS, **units_for(category)}
    drugs = _strength_items(category)

    tokens = tokenize(text)

    def tok_at(k: int) -> str:
        return tokens[k].strip(".") if 0 <= k < len(tokens) else ""

    def item_follows(k: int) -> bool:
        """An item noun at k, allowing modifiers in front of it."""
        while k < len(tokens) and tok_at(k) in modifiers and _match_item(tok_at(k), lexicon) is None:
            k += 1
        return _match_item(tok_at(k), lexicon) is not None

    def number_run(k: int) -> tuple[float | None, int]:
        j = k
        while j < len(tokens) and is_number_token(tok_at(j)):
            j += 1
        return (parse_number_phrase(tokens[k:j]) if j > k else None), j

    # In a LIST -- two or more things named -- a small bare number after an
    # item is its count: "maggi 4 packet, doodh 2 packet, bread 1". Outside a
    # list the same shape is usually the price ("chawal 300"), so the rule is
    # confined to where it is unambiguous.
    is_list = sum(1 for k in range(len(tokens)) if _match_item(tok_at(k), lexicon)) >= 2

    items: list[LineItem] = []
    pending_qty: float | None = None
    pending_unit: str | None = None
    pending_modifier: str | None = None
    #: True when pending_qty came with a unit attached ("2kg"), which makes it
    #: a measure that a later bare number must not overwrite.
    pending_measured = False

    i = 0
    while i < len(tokens):
        tok = tok_at(i)

        if tok == BOUNDARY or tok in _CONJUNCTIONS:
            pending_qty, pending_unit, pending_modifier = None, None, None
            pending_measured = False
            i += 1
            continue

        # Collect a number run.
        if is_number_token(tok):
            value, j = number_run(i)
            nxt = tok_at(j)

            # A number followed by a unit is a MEASURE -- "2kg" is two kilos,
            # and nothing later in the sentence can turn it into something
            # else.
            measured = nxt in units

            # "paune sau ka chai" — a price, not a count. Leaving pending_qty
            # unset keeps the number available as the amount.
            if value is not None and nxt not in _PRICE_MARKERS and nxt not in _EXPLICIT_RATE_MARKERS:
                if pending_qty is not None and pending_measured and not measured:
                    # A BARE number arriving after an established measure is a
                    # price, not a replacement count. Real chat states them in
                    # exactly this order and often across separate messages:
                    #
                    #     "2kg for"  "500 only"  "organic"  "grown"  "tamatar"
                    #
                    # Overwriting here made pending_qty 500 while the unit
                    # "kg" from the measure survived alongside it, so the
                    # entry booked FIVE HUNDRED KILOS of tomatoes. Leaving the
                    # number alone keeps the measure intact and lets the
                    # amount picker claim 500 as the price, which is what it
                    # is.
                    pass
                else:
                    pending_qty = value
                    pending_measured = measured
                    if not measured:
                        # A bare number carries no unit of its own; a unit
                        # left over from an earlier number is not its.
                        pending_unit = None
            # A number between an adjective and a noun breaks them apart: in
            # "3 shirts size L and 2 jeans" the "size" is not the jeans'.
            pending_modifier = None
            i = j
            continue

        # A unit word that is also a thing sold -- box, bottle, dabba -- is
        # a unit only when it measures an item after it: "1 box pen" is a box
        # of pens, "1 geometry box" is a geometry box.
        if tok in units and not (tok in _CONTAINERS and _match_item(tok, lexicon) and not item_follows(i + 1)):
            pending_unit = units[tok]
            i += 1
            continue

        # An adjective in front of a noun belongs to it: "blue kurti" is one
        # item. Held rather than emitted, because a modifier with no noun
        # after it ("blue hai kya") is not an order for anything. Words that
        # are nouns as well -- "chana", "amul", "dettol" -- only qualify the
        # noun that follows them; on their own they are the item.
        if tok in modifiers and (_match_item(tok, lexicon) is None or item_follows(i + 1)):
            pending_modifier = tok
            i += 1
            continue

        # Two nouns that name one garment: "salwar kameez", "kurta pajama".
        matched_item = _match_item(tok, lexicon)
        follower = _match_item(tok_at(i + 1), lexicon)
        if matched_item is not None and follower is not None and is_compound(matched_item, follower):
            pending_modifier = matched_item
            i += 1
            continue

        if matched_item is not None:
            nxt = tok_at(i + 1)
            # A digit or a unit counts as corroboration; a weak number word
            # does not -- "daal do" is "put it in", not two kilos of lentils.
            corroborated = nxt in units or (
                is_number_token(nxt) and nxt not in ("do", "de")
            )

            if matched_item in _WEAK_ITEMS and pending_qty is None and pending_unit is None:
                if not corroborated:
                    i += 1
                    continue

            quantity, unit = pending_qty, pending_unit
            modifier = pending_modifier
            pending_qty, pending_unit, pending_modifier = None, None, None
            pending_measured = False
            i += 1
            name = matched_item

            # A medicine's strength is part of its name: "dolo 650" is a
            # product, not six hundred and fifty of anything, and not Rs 650.
            if matched_item in drugs and _is_numeric_token(tok_at(i)):
                strength, j = number_run(i)
                after = tok_at(j)
                if strength is not None and strength >= 50 and (
                    after in ("mg", "ml", "mcg") or after in _POSSESSIVES
                    or is_number_token(after) or after in (BOUNDARY, "")
                ):
                    name = f"{matched_item} {int(strength) if strength == int(strength) else strength}"
                    i = j + 1 if after in ("mg", "ml", "mcg") else j

            # Trailing quantity: shopkeepers write both "2 kilo chawal" and
            # "chawal 2 kilo". Without this the trailing quantity drifts onto
            # the NEXT item, and the orphaned number gets picked as the
            # amount -- "dal 2 kilo aur atta 5 kilo" booked Rs 5.
            # A trailing number counts as a quantity ONLY when a unit follows
            # it. "chawal 2 kilo" is two kilos; "chawal 300" is three hundred
            # rupees of rice, and swallowing that as a quantity loses the
            # amount entirely. One possessive may sit in between: "biscuit ke
            # 4 packet", "dolo 650 ki 2 strip".
            if quantity is None and i < len(tokens):
                k = i + 1 if tok_at(i) in _POSSESSIVES and is_number_token(tok_at(i + 1)) else i
                lookahead = tok_at(k)
                if is_number_token(lookahead) and lookahead not in ("do", "de"):
                    value, j = number_run(k)
                    if value is not None and tok_at(j) in units:
                        quantity, unit = value, units[tok_at(j)]
                        i = j + 1
                    elif (
                        value is not None and k == i and is_list
                        and value == int(value) and 0 < value <= 12
                        and (tok_at(j) in (BOUNDARY, "") or tok_at(j) in _CONJUNCTIONS
                             or item_follows(j))
                    ):
                        quantity = value
                        i = j

            # A unit AFTER the item, for a count stated before it: "1 salwar
            # kameez set", "2 maggi packet".
            if quantity is not None and unit is None and tok_at(i) in units and tok_at(i) not in _CONTAINERS:
                unit = units[tok_at(i)]
                i += 1

            rate = _trailing_rate(tokens, i, lexicon)
            items.append(
                LineItem(
                    name=name,
                    quantity=quantity,
                    unit=unit,
                    modifier=modifier,
                    unit_price=rate,
                )
            )
            continue

        i += 1

    # One thing named, its measure said somewhere else in the message:
    # "spring onion chahiye, 2 kg", "spring onion bhej do 2kg", "chawal do
    # kilo". The walk only looks beside the item, so the two kilos were lost
    # and a price list charged for one. With a single item there is nothing
    # else the measure could belong to; with two measures it is ambiguous and
    # left alone.
    if len(items) == 1 and items[0].quantity is None:
        from .numbers import _is_verb_not_number

        found: list[tuple[float, str]] = []
        k = 0
        while k < len(tokens):
            if is_number_token(tok_at(k)) and not _is_verb_not_number(tokens, k):
                value, j = number_run(k)
                if value is not None and value > 0 and tok_at(j) in units and tok_at(j) not in _CONTAINERS:
                    found.append((value, units[tok_at(j)]))
                k = max(j, k + 1)
                continue
            k += 1
        if len(set(found)) == 1:
            items[0].quantity, items[0].unit = found[0]
        else:
            # ...or a bare count right before an ordering word: "kurta 1 for
            # 2000, 2 chahiye". Only a small whole number, and only one.
            counts: list[float] = []
            k = 0
            while k < len(tokens):
                if is_number_token(tok_at(k)) and not _is_verb_not_number(tokens, k):
                    value, j = number_run(k)
                    if (
                        value is not None and 0 < value <= 100 and value == int(value)
                        and tok_at(j) in _COUNT_VERBS
                    ):
                        counts.append(value)
                    k = max(j, k + 1)
                    continue
                k += 1
            if len(set(counts)) == 1 and not found:
                items[0].quantity = counts[0]
        # A rate stated before the thing: "2000 each kurta" ("1 for 2000
        # kurta"). Only an EXPLICIT marker counts here: "paune sau ka chai" is
        # seventy-five rupees of tea, not a rate.
        if items[0].unit_price is None:
            k = 0
            while k < len(tokens) and tok_at(k) != BOUNDARY:
                if is_number_token(tok_at(k)) and not _is_verb_not_number(tokens, k):
                    value, j = number_run(k)
                    if value is not None and value > 0 and tok_at(j) in _EXPLICIT_RATE_MARKERS:
                        items[0].unit_price = value
                        break
                    k = max(j, k + 1)
                    continue
                k += 1

    return items


# Words that say "I want this many": the number before them is a count.
_COUNT_VERBS = {
    "chahiye", "chaiye", "chahie", "chaahiye", "chaye", "chaiyay", "chahiyein",
    "dena", "dedo", "bhejna", "bhejo", "bhejdo", "lunga", "loonga", "lungi", "lenge",
    "चाहिए", "चाहिये", "भेजना", "लूंगा", "देना",
}


def _is_numeric_token(tok: str) -> bool:
    return bool(re.fullmatch(r"\d+(?:\.\d+)?", tok))


def _strength_items(category: Category | str | None) -> set[str]:
    """Medicines, whose trailing number is a strength rather than a count."""
    cat = category if isinstance(category, Category) else Category.parse(category)
    if cat not in (Category.PHARMACY, Category.GENERAL):
        return set()
    return pharmacy_items()


def _measure_values(text: str, category: Category | str | None = None) -> set[float]:
    """Every number immediately followed by a unit. These are never money."""
    from .numbers import is_number_token, parse_number_phrase

    units = {**_UNITS, **units_for(category)}
    tokens = tokenize(text)
    found: set[float] = set()
    i = 0
    while i < len(tokens):
        if not is_number_token(tokens[i].strip(".")):
            i += 1
            continue
        j = i
        while j < len(tokens) and is_number_token(tokens[j].strip(".")):
            j += 1
        if j < len(tokens) and tokens[j].strip(".") in units:
            value = parse_number_phrase(tokens[i:j])
            if value is not None:
                found.add(value)
        i = max(j, i + 1)
    return found


def _pick_amount(
    text: str,
    numbers: list[tuple[float, str]],
    items: list[LineItem],
    category: Category | str | None = None,
) -> tuple[float | None, str | None]:
    """
    Choose which number in the message is the money.

    Preference order:
      1. A number adjacent to a currency marker (₹250, 250 rupaye)
      2. A number adjacent to a payment-mode word (dhai sau ka udhaar)
      3. The largest number not already consumed as an item quantity
    """
    if not numbers:
        return None, None

    norm = normalise(text)

    # 1. Currency-adjacent.
    for value, matched in numbers:
        alone = _isolated(norm, matched, numbers)
        number = _whole(matched)
        for pattern in (
            rf"{_CURRENCY.pattern}\s*{number}",
            # A marker with a number right after it is THAT number's prefix:
            # in "bhej do ₹ 2000" the ₹ belongs to 2000, not to the verb "do".
            rf"{number}\s*{_CURRENCY.pattern}(?!\s*\d)",
        ):
            if re.search(pattern, alone, re.IGNORECASE):
                return value, "currency-adjacent"

    # 2. Mode-adjacent -- "dhai sau ka udhaar".
    mode_words = "|".join(re.escape(w) for w in _MODE_WORDS)
    lexicon = items_for(category)
    for value, matched in numbers:
        window = rf"{_whole(matched)}\s+((?:\w+\s+){{0,2}})(?:{mode_words})"
        hit = re.search(window, _isolated(norm, matched, numbers))
        # "2 kurti udhaar 3000": the words between the number and the mode
        # word name the thing it counts, so it is a quantity, not the bill.
        # ...and when another number sits in between, that one is closer to
        # the mode word: "do kurti bhej do 2000 upi se" is Rs 2000, not 2.
        between = hit.group(1).split() if hit else []
        if (
            hit
            and not any(_match_item(w, lexicon) for w in between)
            and not any(is_number_token(w) for w in between)
        ):
            return value, "mode-adjacent"

    # 3. Largest number not already consumed as an item quantity.
    #
    # A number directly followed by a unit is a measure, full stop -- even
    # when a clause boundary stopped it attaching to its item. In "chai
    # khatam ho gayi, 2 packet bhej do jaldi" the 2 never reaches chai, and
    # without this it gets picked as the amount: a ₹2 sale.
    used = {it.quantity for it in items if it.quantity is not None}
    used |= {it.unit_price for it in items if it.unit_price is not None}
    # A medicine's strength is part of its name ("crocin 650"), never Rs 650.
    used |= {float(w) for it in items for w in it.name.split() if _is_numeric_token(w)}
    used |= _measure_values(text, category)
    used |= _count_values(text, category)
    # A rupee or two is not a bill. Below ten, an unattached number is a
    # count the parser could not place -- "2 packet parle g, 1 kurkure"
    # booked Rs 1 -- and a missing amount is recoverable where a wrong one
    # is not. A real small price still arrives through the currency and mode
    # checks above, which do not pass through here.
    spare = [(v, m) for v, m in numbers if v not in used and v >= 10]
    if spare:
        value, matched = max(spare, key=lambda p: p[0])
        return value, "largest-unused"

    # Every number was a quantity, so no price was stated. Falling through to
    # "largest overall" here would reuse a quantity AS the amount: "2 kilo
    # chawal bhej do" booked Rs 2. A missing amount is recoverable -- the
    # confirm sheet asks for it -- but a wrong one is money out of someone's
    # pocket.
    if items:
        return None, None

    # The same holds with no item recognised: a measure is never money.
    # "aadha kilo jeera" -- before jeera was known -- booked Rs 0.50.
    candidates = [(v, m) for v, m in numbers if v not in used and v >= 10]
    if not candidates:
        return None, None
    value, matched = max(candidates, key=lambda p: p[0])
    return value, "largest-overall"


def _whole(matched: str) -> str:
    """
    A number's words as a pattern that only matches the WHOLE number.

    Unanchored, "2" matched the front of "₹ 2000" and "do" the front of "do
    hazar udhaar", so "2 kurti ₹2,000" and "do kurti, do hazar udhaar" both
    booked Rs 2 -- the count read as the bill.
    """
    tail = r"(?![\d])(?!\.\d)" if matched[-1:].isdigit() else r"(?![^\W\d_])"
    return rf"(?<![\w.]){re.escape(matched)}{tail}"


def _isolated(norm: str, matched: str, numbers: list[tuple[float, str]]) -> str:
    """
    The text with every LONGER number that contains this one blanked out.

    "do" is a number on its own and the first word of "do hazar". Testing the
    two-thousand for adjacency is right; letting the two borrow its "udhaar"
    is how a Rs 2,000 credit became Rs 2.
    """
    inner = _whole(matched)
    out = norm
    for _, other in numbers:
        if len(other) > len(matched) and re.search(inner, other):
            out = re.sub(_whole(other), "\x01", out)
    return out


def _count_values(text: str, category: Category | str | None = None) -> set[float]:
    """Every number immediately followed by an item noun -- a count, never money."""
    from .numbers import is_number_token, parse_number_phrase

    lexicon = items_for(category)
    modifiers = modifiers_for(category)
    tokens = tokenize(text)
    found: set[float] = set()
    i = 0
    while i < len(tokens):
        if not is_number_token(tokens[i].strip(".")):
            i += 1
            continue
        j = i
        while j < len(tokens) and is_number_token(tokens[j].strip(".")):
            j += 1
        k = j
        while k < len(tokens) and tokens[k].strip(".") in modifiers:
            k += 1
        if k < len(tokens) and _match_item(tokens[k].strip("."), lexicon):
            value = parse_number_phrase(tokens[i:j])
            if value is not None:
                found.add(value)
        i = max(j, i + 1)
    return found


def apply_rules(text: str, category: Category | str | None = None) -> RuleResult:
    """Run the whole deterministic layer over one message."""
    result = RuleResult()

    result.all_numbers = find_numbers(text)
    result.items = extract_items(text, category)
    if result.items:
        result.resolved.add("items")

    mode, _ = detect_mode(text)
    result.mode = mode
    if mode is not PaymentMode.UNKNOWN:
        result.resolved.add("mode")

    direction = detect_direction(text, category)
    result.direction = direction
    if direction is not Direction.UNKNOWN:
        result.resolved.add("direction")

    amount, source = _pick_amount(text, result.all_numbers, result.items, category)
    result.amount = amount
    result.amount_source = source
    # A stated rate times a stated quantity beats every heuristic below it:
    # both halves came from the message, so the total is arithmetic rather
    # than a guess. "3 kurti 500 each" is 1500, and no number in the text
    # says so.
    line_totals = [it.line_total for it in result.items if it.line_total is not None]
    if line_totals:
        result.amount = round(sum(line_totals), 2)
        result.amount_source = "line-totals"
        result.resolved.add("amount")
        return result

    # One thing, priced for one, no count: "1 for 2000 kurta", "kurti 500
    # each". The price of the one is the bill.
    if (
        result.amount is None and len(result.items) == 1
        and result.items[0].unit_price is not None and result.items[0].quantity is None
    ):
        result.amount = result.items[0].unit_price
        result.amount_source = "rate-of-one"

    # "largest-unused" is a guess, not a resolution -- leave it for the model
    # to confirm rather than claiming certainty we do not have.
    if amount is not None and source in ("currency-adjacent", "mode-adjacent"):
        result.resolved.add("amount")

    return result
