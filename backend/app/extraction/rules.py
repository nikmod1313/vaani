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

from .categories import Category, items_for, modifiers_for, units_for
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
    for pattern in _PAYMENT_PHRASES:
        if re.search(pattern, norm):
            return Direction.PAYMENT
    # Items present means goods moved.
    if extract_items(text, category):
        return Direction.SALE
    return Direction.UNKNOWN


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
    return None


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
    from .numbers import is_number_token, parse_number_phrase

    def number_run(i: int) -> tuple[float | None, int]:
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
            break
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
    lexicon = items_for(category)
    modifiers = modifiers_for(category)
    units = {**_UNITS, **units_for(category)}

    tokens = tokenize(text)
    items: list[LineItem] = []
    pending_qty: float | None = None
    pending_unit: str | None = None
    pending_modifier: str | None = None

    from .numbers import is_number_token, parse_number_phrase

    i = 0
    while i < len(tokens):
        tok = tokens[i].strip(".")

        if tok == BOUNDARY:
            pending_qty, pending_unit, pending_modifier = None, None, None
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

        if tok in units:
            pending_unit = units[tok]
            i += 1
            continue

        # An adjective in front of a noun belongs to it: "blue kurti" is one
        # item. Held rather than emitted, because a modifier with no noun
        # after it ("blue hai kya") is not an order for anything.
        if tok in modifiers:
            pending_modifier = tok
            i += 1
            continue

        matched_item = _match_item(tok, lexicon)
        if matched_item is not None:
            nxt = tokens[i + 1].strip(".") if i + 1 < len(tokens) else ""
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
                    followed_by_unit = j < len(tokens) and tokens[j].strip(".") in units
                    value = parse_number_phrase(tokens[i:j]) if followed_by_unit else None
                    if value is not None:
                        quantity = value
                        unit = units[tokens[j].strip(".")]
                        i = j + 1

            rate = _trailing_rate(tokens, i, lexicon)
            items.append(
                LineItem(
                    name=matched_item,
                    quantity=quantity,
                    unit=unit,
                    modifier=modifier,
                    unit_price=rate,
                )
            )
            continue

        i += 1

    return items


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
    used |= {it.unit_price for it in items if it.unit_price is not None}
    used |= _measure_values(text, category)
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

    # "largest-unused" is a guess, not a resolution -- leave it for the model
    # to confirm rather than claiming certainty we do not have.
    if amount is not None and source in ("currency-adjacent", "mode-adjacent"):
        result.resolved.add("amount")

    return result
