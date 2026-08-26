"""
Confidence scoring and interaction routing.

The score is not decoration -- it decides what the shopkeeper is asked. Three
routes, from the plan:

    > 0.85   auto-fill, single-tap confirm
    0.50-0.85 ask about the uncertain field only
    < 0.50   fall back to buttons

Scoring is per field, because "the amount is certain but the item is a guess"
is the common case and a single blended number throws that away.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

# Field weights for the overall score. Amount dominates: getting it wrong is
# the only error that directly misstates money.
FIELD_WEIGHTS = {
    "amount": 0.40,
    "customer": 0.25,
    "mode": 0.20,
    "items": 0.15,
}

AUTO_THRESHOLD = 0.85
ASK_THRESHOLD = 0.50


class Route(str, Enum):
    AUTO = "auto"        # auto-fill, one tap to confirm
    ASK = "ask"          # query only the weak fields
    MANUAL = "manual"    # fall back to buttons


@dataclass
class FieldScore:
    name: str
    value: object
    score: float
    reason: str

    @property
    def needs_asking(self) -> bool:
        return self.score < AUTO_THRESHOLD


@dataclass
class Confidence:
    fields: dict[str, FieldScore] = field(default_factory=dict)

    @property
    def overall(self) -> float:
        """Weighted mean over the fields we actually have weights for."""
        total_weight = 0.0
        total = 0.0
        for name, weight in FIELD_WEIGHTS.items():
            fs = self.fields.get(name)
            if fs is None:
                continue
            total += fs.score * weight
            total_weight += weight
        return round(total / total_weight, 3) if total_weight else 0.0

    @property
    def route(self) -> Route:
        score = self.overall
        if score >= AUTO_THRESHOLD:
            return Route.AUTO
        if score >= ASK_THRESHOLD:
            return Route.ASK
        return Route.MANUAL

    def weak_fields(self) -> list[str]:
        """Fields worth asking about, weakest first."""
        weak = [f for f in self.fields.values() if f.needs_asking]
        return [f.name for f in sorted(weak, key=lambda f: f.score)]

    def as_dict(self) -> dict:
        return {
            "overall": self.overall,
            "route": self.route.value,
            "fields": {
                name: {"score": fs.score, "reason": fs.reason}
                for name, fs in self.fields.items()
            },
            "ask_about": self.weak_fields(),
        }


# --------------------------------------------------------------------------
# Per-field scoring
# --------------------------------------------------------------------------

def score_amount(amount: float | None, source: str | None, agreed: bool | None) -> FieldScore:
    """
    How much do we trust the amount?

    An explicit currency marker is near-certain. A number sitting next to a
    payment-mode word is strong. Picking the largest leftover number is a
    guess and must never route to auto.
    """
    if amount is None:
        return FieldScore("amount", None, 0.0, "no number found")

    base = {
        "currency-adjacent": 0.96,
        "mode-adjacent": 0.88,
        "largest-unused": 0.55,
        "largest-overall": 0.40,
    }.get(source or "", 0.40)

    reason = f"picked by {source}"
    if agreed is True:
        base = min(1.0, base + 0.06)
        reason += "; model agrees"
    elif agreed is False:
        base *= 0.55
        reason += "; model disagrees"

    return FieldScore("amount", amount, round(base, 3), reason)


def score_customer(name: str | None, match_score: float, is_new: bool) -> FieldScore:
    if not name:
        return FieldScore("customer", None, 0.0, "no name found")
    if is_new:
        # A new customer is not an error, but it deserves a look.
        return FieldScore("customer", name, 0.60, "new customer, no khata yet")
    return FieldScore("customer", name, round(min(match_score, 0.99), 3), "matched existing khata")


def score_mode(mode: str, matched_word: str | None) -> FieldScore:
    if mode == "unknown":
        return FieldScore("mode", mode, 0.25, "no payment word found")
    reason = f"keyword '{matched_word}'" if matched_word else "phrase match"
    return FieldScore("mode", mode, 0.95, reason)


def score_items(
    items: list,
    rates_known: bool = True,
    direction: str = "unknown",
) -> FieldScore:
    # A payment settles a balance; it has no line items. Penalising their
    # absence would route every payment to "ask" about a field that cannot
    # exist.
    if direction == "payment":
        return FieldScore("items", [], 1.0, "not applicable to a payment")

    if not items:
        return FieldScore("items", [], 0.30, "no items recognised")

    labels = [getattr(i, "name", str(i)) for i in items]
    missing_qty = sum(1 for i in items if getattr(i, "quantity", None) is None)

    base = 0.90
    reasons = [f"{len(items)} item(s)"]
    if missing_qty:
        base -= 0.20 * (missing_qty / len(items))
        reasons.append(f"{missing_qty} without quantity")
    if not rates_known:
        base -= 0.25
        reasons.append("rate not stated")

    return FieldScore("items", labels, round(max(base, 0.1), 3), "; ".join(reasons))
