"""
The extraction pipeline.

Orchestrates the four stages from the plan:

    1. deterministic rules
    2. LLM layer (only for what the rules could not resolve)
    3. confidence scoring
    4. name resolution

The LLM layer is injected, so the whole engine runs -- and is testable, and
demoable -- with no API key and no network. That is deliberate: the offline
promise is load-bearing, and a pipeline that cannot run without a model is a
pipeline that cannot run on a shop counter with dead signal.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime
from typing import Protocol

from .confidence import (
    Confidence,
    score_amount,
    score_customer,
    score_items,
    score_mode,
)
from .names import resolve as resolve_name
from .rules import Direction, PaymentMode, RuleResult, apply_rules, detect_mode


class LLMLayer(Protocol):
    """
    Pluggable stage 2.

    Implementations call Claude with a strict JSON schema. `gaps` names the
    fields the rules could not resolve, so the prompt stays narrow.
    """

    def extract(
        self,
        text: str,
        gaps: list[str],
        known_customers: list[str],
        rule_result: RuleResult,
    ) -> dict: ...


@dataclass
class Extraction:
    text: str
    customer: str | None = None
    customer_is_new: bool = False
    amount: float | None = None
    mode: str = PaymentMode.UNKNOWN.value
    direction: str = Direction.UNKNOWN.value
    items: list[str] = field(default_factory=list)
    confidence: dict = field(default_factory=dict)
    source: str = "rules"
    extracted_at: str = ""

    def to_dict(self) -> dict:
        return asdict(self)


def _stated_rate(text: str) -> bool:
    """Did the message state a per-unit rate, or only a total?"""
    lowered = text.lower()
    return any(marker in lowered for marker in ("/kg", "per kg", "rate", "ke hisab", "/-"))


def extract(
    text: str,
    known_customers: list[str] | None = None,
    sender_name: str | None = None,
    llm: LLMLayer | None = None,
) -> Extraction:
    """
    Run one message through the full pipeline.

    `sender_name` is the WhatsApp contact the message came from -- the
    strongest customer signal available, and free.
    """
    known_customers = known_customers or []
    rules = apply_rules(text)

    # --- stage 2: only ask the model about what rules could not settle -----
    gaps = [f for f in ("amount", "mode", "items") if f not in rules.resolved]
    llm_result: dict = {}
    if llm is not None and gaps:
        try:
            llm_result = llm.extract(text, gaps, known_customers, rules) or {}
        except Exception:
            # A model failure degrades to rules-only rather than losing the
            # entry. The confidence score will route it to the user anyway.
            llm_result = {}

    amount = rules.amount if rules.amount is not None else llm_result.get("amount")
    agreed: bool | None = None
    if rules.amount is not None and "amount" in llm_result:
        agreed = abs(float(llm_result["amount"]) - rules.amount) < 0.01

    mode = rules.mode.value
    if mode == PaymentMode.UNKNOWN.value and llm_result.get("mode"):
        mode = llm_result["mode"]

    items = [i.label() for i in rules.items]
    if not items and llm_result.get("items"):
        items = list(llm_result["items"])

    # --- customer: sender name first, then anything the model found -------
    raw_name = sender_name or llm_result.get("customer")
    match = resolve_name(raw_name or "", known_customers)

    # --- stage 3: confidence ----------------------------------------------
    _, mode_word = detect_mode(text)
    conf = Confidence()
    conf.fields["amount"] = score_amount(amount, rules.amount_source, agreed)
    conf.fields["customer"] = score_customer(match.name, match.score, match.is_new)
    conf.fields["mode"] = score_mode(mode, mode_word)
    conf.fields["items"] = score_items(
        rules.items,
        rates_known=_stated_rate(text),
        direction=rules.direction.value,
    )

    return Extraction(
        text=text,
        customer=match.name,
        customer_is_new=match.is_new,
        amount=amount,
        mode=mode,
        direction=rules.direction.value,
        items=items,
        confidence=conf.as_dict(),
        source="rules+llm" if llm_result else "rules",
        extracted_at=datetime.now().isoformat(timespec="seconds"),
    )


def extract_batch(
    texts: list[str],
    known_customers: list[str] | None = None,
    senders: list[str | None] | None = None,
    llm: LLMLayer | None = None,
) -> list[Extraction]:
    """
    Bulk path.

    A real implementation batches the surviving blocks into ONE model call
    rather than N -- see the register. This keeps the same signature so the
    caller does not change when that lands.
    """
    senders = senders or [None] * len(texts)
    return [
        extract(text, known_customers, sender, llm)
        for text, sender in zip(texts, senders)
    ]
