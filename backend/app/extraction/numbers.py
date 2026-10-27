"""
Hindi / Hinglish number-word parsing.

Turns spoken quantities into numbers:

    dhai sau        -> 250
    dedh sau        -> 150
    sava sau        -> 125
    paune do sau    -> 175
    sadhe teen sau  -> 350
    do hazaar paanch sau -> 2500

Handles Latin (Hinglish) and Devanagari spellings, and the many romanisations
shopkeepers actually type (dhai / dhaai / adhai, hazaar / hajar / hazar).

Also runs backwards -- ``to_words`` renders a number as Hindi words, which the
spoken-dashboard summary needs. Same table, one source of truth.
"""

from __future__ import annotations

import re
import unicodedata

# --------------------------------------------------------------------------
# Lexicon
# --------------------------------------------------------------------------

# Units 0-10 plus the irregular 11-99 forms. Hindi numerals are irregular the
# whole way to 100, so there is no generating rule -- it has to be a table.
_UNITS: dict[str, float] = {
    "shunya": 0, "zero": 0,
    "ek": 1, "do": 2, "teen": 3, "tin": 3, "char": 4, "chaar": 4,
    "panch": 5, "paanch": 5, "chhe": 6, "chah": 6, "saat": 7,
    "aath": 8, "ath": 8, "nau": 9, "das": 10, "dus": 10,
    "gyarah": 11, "gyara": 11, "barah": 12, "bara": 12, "terah": 13,
    "tera": 13, "chaudah": 14, "chauda": 14, "pandrah": 15, "pandra": 15,
    "solah": 16, "sola": 16, "satrah": 17, "satra": 17, "atharah": 18,
    "athara": 18, "unnis": 19, "unis": 19, "bees": 20, "bis": 20,
    "ikkis": 21, "bais": 22, "teis": 23, "chaubis": 24, "pachchis": 25,
    "pacchis": 25, "chhabbis": 26, "sattais": 27, "atthais": 28,
    "untis": 29, "tees": 30, "tis": 30, "ikattis": 31, "battis": 32,
    "taintis": 33, "chauntis": 34, "paintis": 35, "chhattis": 36,
    "saintis": 37, "adtis": 38, "untalis": 39, "chalis": 40,
    "chalees": 40, "iktalis": 41, "bayalis": 42, "taintalis": 43,
    "chavalis": 44, "paintalis": 45, "chhiyalis": 46, "santalis": 47,
    "adtalis": 48, "unchas": 49, "pachas": 50, "pachaas": 50,
    "ikyavan": 51, "bavan": 52, "tirpan": 53, "chauvan": 54,
    "pachpan": 55, "chhappan": 56, "sattavan": 57, "atthavan": 58,
    "unsath": 59, "saath": 60, "sath": 60, "iksath": 61, "basath": 62,
    "tirsath": 63, "chausath": 64, "painsath": 65, "chhiyasath": 66,
    "sarsath": 67, "adsath": 68, "unhattar": 69, "sattar": 70,
    "ikhattar": 71, "bahattar": 72, "tihattar": 73, "chauhattar": 74,
    "pachhattar": 75, "chihattar": 76, "sathattar": 77, "athhattar": 78,
    "unasi": 79, "assi": 80, "ikyasi": 81, "byasi": 82, "tirasi": 83,
    "chaurasi": 84, "pachasi": 85, "chhiyasi": 86, "satasi": 87,
    "athasi": 88, "navasi": 89, "nabbe": 90, "ikyanve": 91, "banve": 92,
    "tiranve": 93, "chauranve": 94, "pachanve": 95, "chhiyanve": 96,
    "sattanve": 97, "atthanve": 98, "ninyanve": 99,
}

# Devanagari spellings map onto the same values.
_UNITS_DEVA: dict[str, float] = {
    "एक": 1, "दो": 2, "तीन": 3, "चार": 4, "पांच": 5, "पाँच": 5,
    "छह": 6, "छे": 6, "सात": 7, "आठ": 8, "नौ": 9, "दस": 10,
    "ग्यारह": 11, "बारह": 12, "तेरह": 13, "चौदह": 14, "पंद्रह": 15,
    "सोलह": 16, "सत्रह": 17, "अठारह": 18, "उन्नीस": 19, "बीस": 20,
    "पच्चीस": 25, "तीस": 30, "चालीस": 40, "पचास": 50, "साठ": 60,
    "सत्तर": 70, "अस्सी": 80, "नब्बे": 90,
}
_UNITS.update(_UNITS_DEVA)

# Multipliers. `sau` scales the pending value; hazaar and up close a group.
#
# Deliberately NOT included: "so" (English), "k" (appears everywhere). Their
# collision rate with ordinary text outweighs the recall they buy.
_MULTIPLIERS: dict[str, int] = {
    "sau": 100, "सौ": 100,
    "hazaar": 1000, "hazar": 1000, "hajar": 1000, "hajaar": 1000,
    "sahasra": 1000, "हज़ार": 1000, "हजार": 1000,
    "lakh": 100_000, "lac": 100_000, "lakhs": 100_000, "लाख": 100_000,
    "crore": 10_000_000, "karod": 10_000_000, "करोड़": 10_000_000,
}

# Standalone fractional quantities.
_FRACTIONS: dict[str, float] = {
    "adha": 0.5, "aadha": 0.5, "adh": 0.5, "half": 0.5, "आधा": 0.5,
    "pav": 0.25, "paav": 0.25, "पाव": 0.25,
    "dedh": 1.5, "derh": 1.5, "dedhh": 1.5, "डेढ़": 1.5, "डेढ": 1.5,
    "dhai": 2.5, "dhaai": 2.5, "adhai": 2.5, "adhaai": 2.5,
    "ढाई": 2.5, "अढ़ाई": 2.5,
}

# Prefix modifiers -- they adjust the number that FOLLOWS them.
# With no following number, an implied 1 is used: "sava sau" -> 1.25 * 100.
_MODIFIERS: dict[str, float] = {
    "sava": +0.25, "sawa": +0.25, "सवा": +0.25,
    "paune": -0.25, "pone": -0.25, "पौने": -0.25,
    "sadhe": +0.5, "sade": +0.5, "साढ़े": +0.5, "साढे": +0.5,
}

# Words that may sit between number tokens without breaking the run.
_FILLERS = {"aur", "और", "or"}

# Number words that are also ordinary Hindi words. "do" means 2, but it is far
# more often the imperative "give/send" -- "bhej do", "kar do", "likh do". A
# weak word only counts as a number when the context supports it.
_WEAK_NUMBERS = {"do", "de", "दो", "दे"}

# Verb stems that turn a following "do" into an imperative particle.
_IMPERATIVE_STEMS = {
    "bhej", "bhej", "kar", "likh", "la", "laa", "de", "daal", "dal", "rakh",
    "bana", "banaa", "chadha", "utha", "bhijwa", "dedo", "send", "add",
    "भेज", "कर", "लिख", "ला", "दे", "डाल", "रख",
}

# Marks a clause break. A number phrase never spans one.
BOUNDARY = "\x00"

_DEVA_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")


# --------------------------------------------------------------------------
# Normalisation
# --------------------------------------------------------------------------

_PHONE = re.compile(
    r"\+?91[\s-]?[6-9]\d{4}[\s-]?\d{5}(?!\d)|(?<!\d)[6-9]\d{9}(?!\d)|(?<!\d)[6-9]\d{4}[\s-]\d{5}(?!\d)"
    r"|\(\d{3}\)\s?\d{3}[\s.-]\d{4}(?!\d)|(?<!\d)\d{3}[.-]\d{3}[.-]\d{4}(?!\d)"
)


def normalise(text: str) -> str:
    """
    Lowercase, fold Devanagari digits to ASCII, and mark clause boundaries.

    Boundaries matter: without them "chai bhej do, dhai sau" parses the
    imperative "do" as 2 and yields 450 instead of 250.
    """
    text = unicodedata.normalize("NFC", text)
    text = text.translate(_DEVA_DIGITS)
    text = text.lower()
    # A phone number is never money. Customers send them -- "is number pe
    # call karna", a delivery contact -- and a captured "Save (987)
    # 650-0001?" banner was read as a Rs 987 bill. Removed before any number
    # is read: +91 forms, bare 10-digit mobiles, "98765 43210", and the
    # bracketed and dashed forms a phone's contact UI writes.
    text = _PHONE.sub(" ", text)
    # Detach currency markers from the digits they hug, so "₹250", "rs.250"
    # and "250/-" all yield a bare numeric token.
    text = re.sub(r"(₹|रु\.?)\s*(\d)", r"\1 \2", text)
    text = re.sub(r"\brs\.?\s*(\d)", r"rs \1", text)
    text = re.sub(r"(\d)\s*/-", r"\1 ", text)
    # Digit-group commas belong to the number, not the sentence: "1,250" and
    # the Indian "1,25,000". Left in, the comma became a clause boundary and
    # "total 1,250 hua" was read as Rs 250 -- a thousand rupees lost on one
    # bill. Only a comma followed by exactly three digits (or two digits and
    # another group) is a separator; "2,3 kurti" stays two numbers.
    text = re.sub(r"(?<=\d),(?=\d{2},\d|\d{3}(?!\d))", "", text)
    # Split glued measures: people type "atta 5kg, dal 2kg" far more often
    # than "5 kg". Without this the unit never registers and the item loses
    # its quantity entirely.
    text = re.sub(
        r"(?<=\d)(kgs?|kilos?|gms?|grams?|ml|ltrs?|litres?|liters?|l|pkts?|packets?|pcs?)\b",
        r" \1",
        text,
    )
    # Clause separators become explicit boundary tokens. A period only counts
    # when it is not a decimal point.
    text = re.sub(r"[,،;:।!?\n\r]+", f" {BOUNDARY} ", text)
    text = re.sub(r"(?<!\d)\.(?!\d)", f" {BOUNDARY} ", text)
    text = re.sub(rf"[^\w\s.ऀ-ॿ₹/{BOUNDARY}-]", " ", text)
    return re.sub(r"[ \t]+", " ", text).strip()


def tokenize(text: str) -> list[str]:
    """Tokens, with BOUNDARY markers preserved."""
    return normalise(text).split()


def is_number_token(tok: str) -> bool:
    """True if the token participates in a number phrase."""
    t = tok.strip(".")
    if t == BOUNDARY or not t:
        return False
    return (
        t in _UNITS
        or t in _MULTIPLIERS
        or t in _FRACTIONS
        or t in _MODIFIERS
        or _is_numeric(t)
    )


def _is_verb_not_number(tokens: list[str], i: int) -> bool:
    """
    Decide whether a weak number word is actually a verb here.

    "do" counts as 2 when it leads into a unit or another number
    ("do kilo", "do hazaar"). It is the imperative "give" when it trails a
    verb stem ("bhej do") or ends the clause ("likh do.").
    """
    tok = tokens[i].strip(".")
    if tok not in _WEAK_NUMBERS:
        return False

    prev = tokens[i - 1].strip(".") if i > 0 else ""
    if prev in _IMPERATIVE_STEMS:
        return True

    nxt = tokens[i + 1].strip(".") if i + 1 < len(tokens) else BOUNDARY
    # Nothing meaningful follows -> imperative.
    if nxt in (BOUNDARY, ""):
        return True
    # Followed by a multiplier or another number, it is a real quantity.
    if nxt in _MULTIPLIERS or is_number_token(nxt):
        return False
    # Followed by a unit or item word, also a real quantity ("do kilo").
    return False


def _is_numeric(tok: str) -> bool:
    return bool(re.fullmatch(r"\d+(?:\.\d+)?", tok))


# --------------------------------------------------------------------------
# Parsing
# --------------------------------------------------------------------------

def parse_number_phrase(tokens: list[str]) -> float | None:
    """
    Parse a run of number tokens into a single value.

    Uses the standard total/current accumulator: `sau` scales the pending
    value, while `hazaar` and above close the current group and add it to the
    running total. That makes compounds work -- "do hazaar paanch sau" is
    2*1000 then 5*100, giving 2500.
    """
    total = 0.0
    current: float | None = None
    modifier: float | None = None
    seen_any = False

    for raw in tokens:
        tok = raw.strip(".")
        if not tok or tok in _FILLERS:
            continue

        if tok in _MODIFIERS:
            modifier = _MODIFIERS[tok]
            seen_any = True
            continue

        if tok in _FRACTIONS:
            current = (current or 0) + _FRACTIONS[tok]
            seen_any = True
            continue

        if _is_numeric(tok):
            value = float(tok)
            if modifier is not None:
                value += modifier
                modifier = None
            current = value if current is None else current + value
            seen_any = True
            continue

        if tok in _UNITS:
            value = _UNITS[tok]
            if modifier is not None:
                value += modifier
                modifier = None
            current = value if current is None else current + value
            seen_any = True
            continue

        if tok in _MULTIPLIERS:
            mult = _MULTIPLIERS[tok]
            base = current
            if base is None:
                # "sava sau" / "paune sau" -- the modifier applies to an
                # implied 1, giving 125 and 75 respectively.
                base = 1.0
                if modifier is not None:
                    base += modifier
                    modifier = None
            if mult >= 1000:
                total += base * mult
                current = None
            else:
                current = base * mult
            seen_any = True
            continue

        # Any other word ends the number phrase.
        break

    if not seen_any:
        return None

    # A dangling modifier with nothing after it ("sava" alone) means 1.25.
    if current is None and modifier is not None:
        current = 1.0 + modifier

    result = total + (current or 0.0)
    return result if result or seen_any else None


def find_numbers(text: str) -> list[tuple[float, str]]:
    """
    Find every number phrase in free text.

    Returns (value, matched_text) pairs in the order they appear.
    """
    tokens = tokenize(text)

    # Neutralise weak number words used as verbs before scanning, so they
    # break runs exactly like a clause boundary would.
    tokens = [
        BOUNDARY if _is_verb_not_number(tokens, i) else t
        for i, t in enumerate(tokens)
    ]

    found: list[tuple[float, str]] = []
    i = 0
    while i < len(tokens):
        if not is_number_token(tokens[i]):
            i += 1
            continue

        # Greedily take the longest run of number tokens, allowing fillers
        # in the middle but never at the end.
        j = i
        end = i
        while j < len(tokens):
            if is_number_token(tokens[j]):
                j += 1
                end = j
            elif tokens[j] in _FILLERS and j + 1 < len(tokens) and is_number_token(tokens[j + 1]):
                j += 1
            else:
                break

        run = tokens[i:end]
        value = parse_number_phrase(run)
        if value is not None:
            found.append((value, " ".join(run)))
        i = max(end, i + 1)
    return found


def parse_number(text: str) -> float | None:
    """Convenience: the first number found in the text, if any."""
    results = find_numbers(text)
    return results[0][0] if results else None


# --------------------------------------------------------------------------
# Reverse: number -> Hindi words (for the spoken dashboard)
# --------------------------------------------------------------------------

_ONES_OUT = [
    "shunya", "ek", "do", "teen", "chaar", "paanch", "chhe", "saat", "aath",
    "nau", "das", "gyarah", "barah", "terah", "chaudah", "pandrah", "solah",
    "satrah", "atharah", "unnis",
]

# Inverted from the parse table so the two directions cannot drift apart.
_TENS_OUT = {
    20: "bees", 30: "tees", 40: "chalees", 50: "pachas", 60: "saath",
    70: "sattar", 80: "assi", 90: "nabbe",
}
_EXACT_OUT = {
    v: k for k, v in _UNITS.items()
    if isinstance(v, (int, float)) and 20 <= v <= 99
}


def _two_digit_words(n: int) -> str:
    if n < 20:
        return _ONES_OUT[n]
    if n in _TENS_OUT:
        return _TENS_OUT[n]
    if n in _EXACT_OUT:
        return _EXACT_OUT[n]
    tens, ones = divmod(n, 10)
    return f"{_TENS_OUT[tens * 10]} {_ONES_OUT[ones]}"


def to_words(amount: float) -> str:
    """
    Render a number as Hindi words, Indian grouping (lakh / hazaar / sau).

        4240 -> "chaar hazaar do sau chalees"

    Used by the summary composer so Bulbul speaks exact figures instead of a
    model paraphrasing them.
    """
    n = int(round(amount))
    if n == 0:
        return "shunya"
    if n < 0:
        return "minus " + to_words(-n)

    parts: list[str] = []
    for divisor, name in ((10_000_000, "crore"), (100_000, "lakh"), (1000, "hazaar")):
        if n >= divisor:
            count, n = divmod(n, divisor)
            parts.append(f"{to_words(count)} {name}")

    if n >= 100:
        count, n = divmod(n, 100)
        parts.append(f"{_two_digit_words(count)} sau")
    if n:
        parts.append(_two_digit_words(n))

    return " ".join(parts)


def rupees_to_words(amount: float) -> str:
    """Amount as spoken rupees, including paise when they are non-zero."""
    whole = int(amount)
    paise = int(round((amount - whole) * 100))
    text = f"{to_words(whole)} rupaye"
    if paise:
        text += f" {to_words(paise)} paise"
    return text
