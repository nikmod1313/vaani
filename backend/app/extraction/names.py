"""
Customer name resolution.

Two jobs: fold Devanagari and Latin spellings of the same name together
(राहुल == Rahul), then fuzzy-match against the shop's existing customers so
the khata does not sprout three Rahuls.

Uses stdlib difflib rather than RapidFuzz so the engine has no hard
dependency. `token_set_ratio` reimplements RapidFuzz's algorithm; swap the
import in one place if you later want the faster C version.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher

# --------------------------------------------------------------------------
# Devanagari -> Latin transliteration
# --------------------------------------------------------------------------

_CONSONANTS = {
    "क": "k", "ख": "kh", "ग": "g", "घ": "gh", "ङ": "ng",
    "च": "ch", "छ": "chh", "ज": "j", "झ": "jh", "ञ": "n",
    "ट": "t", "ठ": "th", "ड": "d", "ढ": "dh", "ण": "n",
    "त": "t", "थ": "th", "द": "d", "ध": "dh", "न": "n",
    "प": "p", "फ": "ph", "ब": "b", "भ": "bh", "म": "m",
    "य": "y", "र": "r", "ल": "l", "व": "v", "ळ": "l",
    "श": "sh", "ष": "sh", "स": "s", "ह": "h",
    "क़": "q", "ख़": "kh", "ग़": "g", "ज़": "z", "ड़": "r",
    "ढ़": "rh", "फ़": "f",
}

_VOWELS = {
    "अ": "a", "आ": "aa", "इ": "i", "ई": "ee", "उ": "u", "ऊ": "oo",
    "ऋ": "ri", "ए": "e", "ऐ": "ai", "ओ": "o", "औ": "au",
}

_MATRAS = {
    "ा": "a", "ि": "i", "ी": "i", "ु": "u", "ू": "u", "ृ": "ri",
    "े": "e", "ै": "ai", "ो": "o", "ौ": "au",
}

_SIGNS = {"ं": "n", "ँ": "n", "ः": "h"}

_VIRAMA = "्"
_NUKTA = "़"


def transliterate(text: str) -> str:
    """
    Devanagari to a rough Latin form good enough for fuzzy matching.

    Not a scholarly scheme -- it only has to make राहुल and Rahul land close
    enough that the matcher pairs them.
    """
    text = unicodedata.normalize("NFC", text)
    out: list[str] = []
    i = 0
    # Only an *inherent* trailing vowel is an artefact. A matra vowel is real,
    # and Latin input must not be touched at all.
    ends_with_inherent = False

    while i < len(text):
        ch = text[i]

        if ch == _NUKTA:
            i += 1
            continue

        if ch in _CONSONANTS:
            out.append(_CONSONANTS[ch])
            nxt = text[i + 1] if i + 1 < len(text) else ""
            if nxt == _VIRAMA:
                ends_with_inherent = False
                i += 2          # cluster: no inherent vowel
                continue
            if nxt in _MATRAS:
                out.append(_MATRAS[nxt])
                ends_with_inherent = False
                i += 2
                continue
            out.append("a")      # inherent vowel
            ends_with_inherent = True
            i += 1
            continue

        if ch in _VOWELS:
            out.append(_VOWELS[ch])
        elif ch in _MATRAS:
            out.append(_MATRAS[ch])
        elif ch in _SIGNS:
            out.append(_SIGNS[ch])
        elif ch == _VIRAMA:
            pass
        else:
            out.append(ch)
        ends_with_inherent = False
        i += 1

    result = "".join(out)
    # "रमेश" transliterates to "ramesha"; drop that final inherent vowel.
    if ends_with_inherent and len(result) > 3:
        result = result[:-1]
    return result


def normalise_name(name: str) -> str:
    """Canonical form for comparison: transliterated, lowercased, honorifics off."""
    name = transliterate(name.strip())
    name = name.lower()
    name = re.sub(r"[^a-z\s]", " ", name)
    # Honorifics and address terms carry no identity.
    name = re.sub(
        r"\b(ji|bhai|bhaiya|behen|didi|sahab|saheb|shri|smt|mr|mrs|ms|uncle|aunty)\b",
        " ",
        name,
    )
    return re.sub(r"\s+", " ", name).strip()


# --------------------------------------------------------------------------
# Fuzzy matching
# --------------------------------------------------------------------------

def _ratio(a: str, b: str) -> float:
    return SequenceMatcher(None, a, b).ratio()


def token_set_ratio(a: str, b: str) -> float:
    """
    RapidFuzz's token_set_ratio, on stdlib.

    Order-insensitive and tolerant of extra words, so "Rahul Verma" matches
    "Verma Rahul" and "Rahul" alike.
    """
    ta, tb = set(a.split()), set(b.split())
    if not ta or not tb:
        return 0.0

    common = " ".join(sorted(ta & tb))
    rest_a = " ".join(sorted(ta - tb))
    rest_b = " ".join(sorted(tb - ta))

    s1 = f"{common} {rest_a}".strip()
    s2 = f"{common} {rest_b}".strip()

    if not common:
        return _ratio(s1, s2)
    return max(_ratio(common, s1), _ratio(common, s2), _ratio(s1, s2))


@dataclass
class NameMatch:
    name: str | None
    score: float
    is_new: bool

    @property
    def confident(self) -> bool:
        return self.score >= 0.85


def resolve(candidate: str, known: list[str], threshold: float = 0.72) -> NameMatch:
    """
    Match a spoken/typed name against the shop's customer list.

    Below the threshold we return the raw name flagged as new rather than
    forcing a bad match -- merging two customers is far worse than creating
    one duplicate the shopkeeper can fix.
    """
    if not candidate or not candidate.strip():
        return NameMatch(name=None, score=0.0, is_new=False)

    target = normalise_name(candidate)
    if not target:
        return NameMatch(name=None, score=0.0, is_new=False)

    best_name, best_score = None, 0.0
    for existing in known:
        score = token_set_ratio(target, normalise_name(existing))
        if score > best_score:
            best_name, best_score = existing, score

    if best_name is not None and best_score >= threshold:
        return NameMatch(name=best_name, score=best_score, is_new=False)

    return NameMatch(name=candidate.strip(), score=best_score, is_new=True)
