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

# Phrases meaning "write it down" -> credit, even without the word udhaar.
_CREDIT_PHRASES = [
    r"\blikh\s+(?:lena|lo|dena|do|dijiye)\b",
    r"\bkhaate?\s+mein\b",
    r"\bलिख\s+(?:लेना|लो|देना)\b",
]

# Phrases meaning money came back in.
_PAYMENT_PHRASES = [
    r"\bpayment\s+(?:mila|aaya|kiya|kar\s+diya|ho\s+gaya)\b",
    r"\bpaise?\s+(?:mil|aa|de|bhej)\w*\b",
    r"\bjama\s+kar\w*\b",
    r"\bchukta\b", r"\bsettle\w*\b", r"\bclear\s+kar\w*\b",
    r"\bde\s+diye?\b", r"\bbhej\s+diye?\b",
    r"\bपैसे?\s+(?:मिल|आ|दे)\w*\b", r"\bजमा\b",
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
    "agarbatti", "अगरबत्ती", "matchbox", "diya", "mombatti",
}

_CURRENCY = re.compile(r"(?:₹|rs\.?|rupaye|rupees|रु\.?|रुपये)", re.IGNORECASE)


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
    """Payment mode plus the word that decided it."""
    norm = normalise(text)
    for tok in norm.split():
        clean = tok.strip(".")
        if clean in _MODE_WORDS:
            return _MODE_WORDS[clean], clean
    for pattern in _CREDIT_PHRASES:
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
            if value is not None:
                pending_qty = value
            i = j
            continue

        if tok in _UNITS:
            pending_unit = _UNITS[tok]
            i += 1
            continue

        if tok in _ITEMS:
            items.append(LineItem(name=tok, quantity=pending_qty, unit=pending_unit))
            pending_qty, pending_unit = None, None
            i += 1
            continue

        i += 1

    return items


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

    # 3. Largest number not used as an item quantity.
    used = {it.quantity for it in items if it.quantity is not None}
    spare = [(v, m) for v, m in numbers if v not in used]
    if spare:
        value, matched = max(spare, key=lambda p: p[0])
        return value, "largest-unused"

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
