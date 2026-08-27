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

from .numbers import BOUNDARY, find_numbers, normalise, tokenize


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
    r"\blikh\s+(?:lena|lo|dena|do|dijiye)\b",
    r"\bkhaate?\s+mein\b",
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
_PAYMENT_PHRASES = [
    # explicit payment nouns
    r"\bpayment\b.{0,20}?\b(?:mila|mile|aaya|aa\s+gaya|hua|kiya|kar\s+diy[ae])\b",
    r"\bpaise?\b.{0,15}?\b(?:mil|aa|de|bhej|diy)\w*\b",
    r"\bmil\s+gaye?\b",
    # settling up
    r"\bjama\s+kar\w*\b", r"\bchukta\b", r"\bsettle\w*\b",
    r"\b(?:account|khata|hisab|hisaab)\b.{0,15}?\bclear\b",
    r"\bclear\s+kar\s+diy[ae]\b",
    # past-tense giving/sending, the most common form by far
    r"\b(?:de|bhej|kar|jama)\s+diy[ae]\b",
    r"\bbheja\b", r"\bbheje\b",
    r"\b(?:nakad|nagad|cash|rokad|upi|online)\s+diy[ae]\b",
    # a payment app plus any completed action
    r"\b(?:phonepe|gpay|googlepay|paytm|bhim|upi|online|scan|transfer)\b"
    r".{0,25}?\b(?:kar\s+diy[ae]|bhej\s+diy[ae]|diy[ae]|kiya|bheja|bheje|mila|mile)\b",
    # Devanagari -- whitespace boundaries, see DEVA_START above
    DEVA_START + r"पैसे?\s*(?:मिल|आ|दे)\w*",
    DEVA_START + r"जमा" + DEVA_END,
    DEVA_START + r"(?:भेज|कर|दे)\s*(?:दिया|दिये|दिए)" + DEVA_END,
    DEVA_START + r"मिल(?:े|गया|गए)" + DEVA_END,
    DEVA_START + r"भेजे" + DEVA_END,
]

_UNITS: dict[str, str] = {
    "kilo": "kg", "kg": "kg", "kilos": "kg", "किलो": "kg",
    "gram": "g", "gm": "g", "g": "g", "ग्राम": "g",
    "litre": "l", "liter": "l", "ltr": "l", "l": "l", "लीटर": "l",
    "ml": "ml",
    "packet": "packet", "pkt": "packet", "पैकेट": "packet",
    "dozen": "dozen", "darjan": "dozen", "दर्जन": "dozen",
    "piece": "pc", "pcs": "pc", "pc": "pc", "nag": "pc", "अदद": "pc",
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

_CURRENCY = re.compile(r"(?:₹|rs\.?|rupaye|rupees|रु\.?|रुपये)", re.IGNORECASE)

# Tokens that mark the number before them as MONEY rather than a count.
#
# "ka / ki / ke" is the Hindi possessive: "paune sau ka chai" is seventy-five
# rupees WORTH of tea, not seventy-five teas. Without this the price is
# swallowed as a quantity and the entry saves with no amount at all.
_PRICE_MARKERS = {
    "ka", "ki", "ke", "का", "की", "के",
    "rupaye", "rupees", "rupay", "rs", "₹", "रुपये", "रुपए",
}


# --------------------------------------------------------------------------
# Result types
# --------------------------------------------------------------------------

@dataclass
class LineItem:
    name: str
    quantity: float | None = None
    unit: str | None = None

    def label(self) -> str:
        if self.quantity is None:
            return self.name
        qty = int(self.quantity) if self.quantity == int(self.quantity) else self.quantity
        return f"{self.name} {qty}{self.unit or ''}".strip()


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


def detect_direction(text: str) -> Direction:
    """Is this goods going out, or money coming back?"""
    norm = normalise(text)
    for pattern in _PAYMENT_PHRASES:
        if re.search(pattern, norm):
            return Direction.PAYMENT
    # Items present means goods moved.
    if extract_items(text):
        return Direction.SALE
    return Direction.UNKNOWN


def extract_items(text: str) -> list[LineItem]:
    """
    Pull item / quantity / unit triples.

    Walks the token stream tracking the most recent number and unit, so
    "2 kilo chawal" and "chawal 2 kilo" both resolve.
    """
    tokens = tokenize(text)
    items: list[LineItem] = []
    pending_qty: float | None = None
    pending_unit: str | None = None

    from .numbers import is_number_token, parse_number_phrase

    i = 0
    while i < len(tokens):
        tok = tokens[i].strip(".")

        if tok == BOUNDARY:
            pending_qty, pending_unit = None, None
            i += 1
            continue

        # Collect a number run.
        if is_number_token(tok):
            j = i
            while j < len(tokens) and is_number_token(tokens[j].strip(".")):
                j += 1
            value = parse_number_phrase(tokens[i:j])
            nxt = tokens[j].strip(".") if j < len(tokens) else ""
            # "paune sau ka chai" — a price, not a count. Leaving pending_qty
            # unset keeps the number available as the amount.
            if value is not None and nxt not in _PRICE_MARKERS:
                pending_qty = value
            i = j
            continue

        if tok in _UNITS:
            pending_unit = _UNITS[tok]
            i += 1
            continue

        if tok in _ITEMS:
            nxt = tokens[i + 1].strip(".") if i + 1 < len(tokens) else ""
            # A digit or a unit counts as corroboration; a weak number word
            # does not -- "daal do" is "put it in", not two kilos of lentils.
            corroborated = nxt in _UNITS or (
                is_number_token(nxt) and nxt not in ("do", "de")
            )

            if tok in _WEAK_ITEMS and pending_qty is None and pending_unit is None:
                if not corroborated:
                    i += 1
                    continue

            quantity, unit = pending_qty, pending_unit
            pending_qty, pending_unit = None, None
            i += 1

            # Trailing quantity: shopkeepers write both "2 kilo chawal" and
            # "chawal 2 kilo". Without this the trailing quantity drifts onto
            # the NEXT item, and the orphaned number gets picked as the
            # amount -- "dal 2 kilo aur atta 5 kilo" booked Rs 5.
            # A trailing number counts as a quantity ONLY when a unit follows
            # it. "chawal 2 kilo" is two kilos; "chawal 300" is three hundred
            # rupees of rice, and swallowing that as a quantity loses the
            # amount entirely.
            if quantity is None and i < len(tokens):
                lookahead = tokens[i].strip(".")
                if is_number_token(lookahead) and lookahead not in ("do", "de"):
                    j = i
                    while j < len(tokens) and is_number_token(tokens[j].strip(".")):
                        j += 1
                    followed_by_unit = j < len(tokens) and tokens[j].strip(".") in _UNITS
                    value = parse_number_phrase(tokens[i:j]) if followed_by_unit else None
                    if value is not None:
                        quantity = value
                        unit = _UNITS[tokens[j].strip(".")]
                        i = j + 1

            items.append(LineItem(name=tok, quantity=quantity, unit=unit))
            continue

        i += 1

    return items


def _measure_values(text: str) -> set[float]:
    """Every number immediately followed by a unit. These are never money."""
    from .numbers import is_number_token, parse_number_phrase

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
        if j < len(tokens) and tokens[j].strip(".") in _UNITS:
            value = parse_number_phrase(tokens[i:j])
            if value is not None:
                found.add(value)
        i = max(j, i + 1)
    return found


def _pick_amount(
    text: str,
    numbers: list[tuple[float, str]],
    items: list[LineItem],
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
        for pattern in (
            rf"{_CURRENCY.pattern}\s*{re.escape(matched)}",
            rf"{re.escape(matched)}\s*{_CURRENCY.pattern}",
        ):
            if re.search(pattern, norm, re.IGNORECASE):
                return value, "currency-adjacent"

    # 2. Mode-adjacent -- "dhai sau ka udhaar".
    mode_words = "|".join(re.escape(w) for w in _MODE_WORDS)
    for value, matched in numbers:
        window = rf"{re.escape(matched)}\s+(?:\w+\s+){{0,2}}(?:{mode_words})"
        if re.search(window, norm):
            return value, "mode-adjacent"

    # 3. Largest number not already consumed as an item quantity.
    #
    # A number directly followed by a unit is a measure, full stop -- even
    # when a clause boundary stopped it attaching to its item. In "chai
    # khatam ho gayi, 2 packet bhej do jaldi" the 2 never reaches chai, and
    # without this it gets picked as the amount: a ₹2 sale.
    used = {it.quantity for it in items if it.quantity is not None}
    used |= _measure_values(text)
    spare = [(v, m) for v, m in numbers if v not in used]
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

    value, matched = max(numbers, key=lambda p: p[0])
    return value, "largest-overall"


def apply_rules(text: str) -> RuleResult:
    """Run the whole deterministic layer over one message."""
    result = RuleResult()

    result.all_numbers = find_numbers(text)
    result.items = extract_items(text)
    if result.items:
        result.resolved.add("items")

    mode, _ = detect_mode(text)
    result.mode = mode
    if mode is not PaymentMode.UNKNOWN:
        result.resolved.add("mode")

    direction = detect_direction(text)
    result.direction = direction
    if direction is not Direction.UNKNOWN:
        result.resolved.add("direction")

    amount, source = _pick_amount(text, result.all_numbers, result.items)
    result.amount = amount
    result.amount_source = source
    # "largest-unused" is a guess, not a resolution -- leave it for the model
    # to confirm rather than claiming certainty we do not have.
    if amount is not None and source in ("currency-adjacent", "mode-adjacent"):
        result.resolved.add("amount")

    return result
