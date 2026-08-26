"""
The spoken daily summary.

Composes narration text from real aggregates using per-language TEMPLATES.
The model never sees the numbers and never restates them.

That constraint is the whole design. An LLM that paraphrases Rs 4,240 as
"about four thousand" has just lied to a shopkeeper about his own money, and
he has no way to catch it -- he is listening, not reading. So: the store
computes, a template formats, the number formatter spells it out, and Bulbul
only reads aloud what was already decided.

Amounts are rendered as words by `numbers.rupees_to_words`, which is the
parser's table run backwards. One source of truth for "dhai sau" == 250 in
both directions.
"""

from __future__ import annotations

from dataclasses import dataclass

from .extraction.numbers import rupees_to_words

# Sarvam Bulbul v3 language codes.
SUPPORTED = {
    "hi-IN": "Hindi",
    "en-IN": "English",
    "mr-IN": "Marathi",
    "bn-IN": "Bengali",
    "gu-IN": "Gujarati",
    "kn-IN": "Kannada",
    "ml-IN": "Malayalam",
    "od-IN": "Odia",
    "pa-IN": "Punjabi",
    "ta-IN": "Tamil",
    "te-IN": "Telugu",
}

DEFAULT_LANGUAGE = "hi-IN"


@dataclass
class Narration:
    text: str
    language_code: str
    parts: dict[str, str]

    def __len__(self) -> int:
        return len(self.text)


# --------------------------------------------------------------------------
# Templates
# --------------------------------------------------------------------------
#
# Each language supplies the same set of clauses. Written out in full rather
# than machine-translated, because a mistranslated money sentence is worse
# than an untranslated one -- languages beyond these three should be reviewed
# by a speaker before they ship. `mr-IN` here is a starting draft, flagged as
# such in the report.

_TEMPLATES: dict[str, dict[str, str]] = {
    "hi-IN": {
        "greeting": "Namaste {shop}.",
        "sale": "Aaj ki sale {sale} rahi.",
        "cash": "Usme nakad aur online milakar {cash} aaya.",
        "udhaar": "Udhaar {udhaar} gaya.",
        "no_udhaar": "Aaj koi udhaar nahi hua.",
        "outstanding": "Kul baaki {outstanding} hai.",
        "entries": "{entries} entry hui, jisme se {auto} apne aap bhar gayi.",
        "overdue_one": "{name} ka {amount}, {days} din se baaki hai.",
        "overdue_many": "{count} grahak tees din se zyada purane hain. Sabse purana {name} ka {amount} hai.",
        "quiet": "Aaj koi entry nahi hui.",
        "closing": "Dhanyavaad.",
    },
    "en-IN": {
        "greeting": "Good evening, {shop}.",
        "sale": "Today's sale was {sale}.",
        "cash": "Of that, {cash} came in as cash and online.",
        "udhaar": "{udhaar} went on credit.",
        "no_udhaar": "There was no credit today.",
        "outstanding": "Total outstanding is {outstanding}.",
        "entries": "{entries} entries were made, of which {auto} filled in automatically.",
        "overdue_one": "{name} owes {amount}, pending {days} days.",
        "overdue_many": "{count} customers are more than thirty days overdue. The oldest is {name} at {amount}.",
        "quiet": "There were no entries today.",
        "closing": "Thank you.",
    },
    "mr-IN": {
        "greeting": "Namaskar {shop}.",
        "sale": "Aajchi vikri {sale} zali.",
        "cash": "Tyat rokh ani online milun {cash} aale.",
        "udhaar": "Udhar {udhaar} gele.",
        "no_udhaar": "Aaj kahi udhar zale nahi.",
        "outstanding": "Ekun baki {outstanding} aahe.",
        "entries": "{entries} nondi zalya, tyapaiki {auto} apoap bharlya.",
        "overdue_one": "{name} yanche {amount}, {days} divsanpasun baki aahe.",
        "overdue_many": "{count} grahak tees divsanhun jast baki aahet. Sarvat juna {name} yancha {amount} aahe.",
        "quiet": "Aaj kahi nond zali nahi.",
        "closing": "Dhanyavaad.",
    },
}


def _money(amount: float, language: str) -> str:
    """
    Spell an amount for speech.

    Indian-language templates use the Hindi number words the parser already
    knows. English keeps digits -- Bulbul reads "4,240 rupees" correctly, and
    forcing Hindi words into an English sentence sounds wrong.
    """
    if language == "en-IN":
        return f"{amount:,.0f} rupees"
    return rupees_to_words(amount)


def compose(summary: dict, language: str = DEFAULT_LANGUAGE) -> Narration:
    """
    Build the narration for one shop's day.

    `summary` is exactly what `LedgerStore.summary()` returns, so the spoken
    text and the dashboard can never disagree -- they read the same numbers.
    """
    if language not in _TEMPLATES:
        language = DEFAULT_LANGUAGE

    t = _TEMPLATES[language]
    shop = (summary.get("shop") or {}).get("name", "")
    parts: dict[str, str] = {}
    lines: list[str] = []

    if shop:
        parts["greeting"] = t["greeting"].format(shop=shop)
        lines.append(parts["greeting"])

    if not summary.get("entry_count"):
        parts["quiet"] = t["quiet"]
        lines.append(parts["quiet"])
        if summary.get("total_outstanding"):
            parts["outstanding"] = t["outstanding"].format(
                outstanding=_money(summary["total_outstanding"], language)
            )
            lines.append(parts["outstanding"])
        lines.append(t["closing"])
        return Narration(" ".join(lines), language, parts)

    parts["sale"] = t["sale"].format(sale=_money(summary["today_sale"], language))
    lines.append(parts["sale"])

    if summary.get("cash_in"):
        parts["cash"] = t["cash"].format(cash=_money(summary["cash_in"], language))
        lines.append(parts["cash"])

    if summary.get("udhaar_today"):
        parts["udhaar"] = t["udhaar"].format(
            udhaar=_money(summary["udhaar_today"], language)
        )
    else:
        parts["udhaar"] = t["no_udhaar"]
    lines.append(parts["udhaar"])

    if summary.get("total_outstanding"):
        parts["outstanding"] = t["outstanding"].format(
            outstanding=_money(summary["total_outstanding"], language)
        )
        lines.append(parts["outstanding"])

    parts["entries"] = t["entries"].format(
        entries=summary["entry_count"], auto=summary.get("auto_filled", 0)
    )
    lines.append(parts["entries"])

    # Overdue is the part he actually acts on, so it goes last -- it is what
    # he will still be holding when the audio stops.
    overdue = summary.get("overdue") or []
    if overdue:
        worst = max(overdue, key=lambda c: c["days_overdue"])
        if len(overdue) == 1:
            parts["overdue"] = t["overdue_one"].format(
                name=worst["name"],
                amount=_money(worst["outstanding"], language),
                days=worst["days_overdue"],
            )
        else:
            parts["overdue"] = t["overdue_many"].format(
                count=len(overdue),
                name=worst["name"],
                amount=_money(worst["outstanding"], language),
            )
        lines.append(parts["overdue"])

    lines.append(t["closing"])
    return Narration(" ".join(lines), language, parts)
