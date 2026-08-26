"""
Stage 2 of the extraction pipeline: Claude.

Only runs for fields the deterministic rules could not settle. The prompt is
built narrow on purpose -- it is told exactly which gaps to fill, given the
shop's own customer and item vocabulary, and shown that shop's past
corrections as few-shot examples.

Design rules worth keeping:

  * The rules layer wins on anything it resolved. The model fills gaps; it
    does not overrule arithmetic that already succeeded.
  * A failure here degrades to rules-only, never to a lost entry.
  * Structured output is validated by Pydantic, so a malformed response is a
    caught exception rather than a bad ledger row.
"""

from __future__ import annotations

import os
from typing import Literal

from pydantic import BaseModel, Field

from .rules import RuleResult

MODEL = "claude-opus-5"
MAX_TOKENS = 4000

# Field-level guidance the model sees. Kept beside the schema so the two
# cannot drift apart.
_FIELD_HELP = {
    "amount": "the total money for this transaction, as a number. NOT a quantity.",
    "mode": "cash / upi / udhaar (credit). 'likh lena' or 'khaate mein' means udhaar.",
    "items": "goods mentioned, each as 'name qty unit' where stated.",
    "customer": "the person's name, if one appears in the text itself.",
}

SYSTEM_PROMPT = """\
You extract shop transactions from Hindi, Hinglish and Devanagari messages \
sent to a small Indian kirana store.

You are the second stage of a pipeline. A deterministic rules engine has \
already run and resolved some fields. Your job is ONLY to fill the gaps it \
names. Do not contradict a field it already resolved.

Rules that matter:

- Amounts are money, never quantities. In "2 kilo chawal, dhai sau ka \
udhaar", the amount is 250 and 2 is a quantity.
- Hindi number words: dhai=2.5, dedh=1.5, sava=+0.25, paune=-0.25, \
sadhe=+0.5, sau=100, hazaar=1000, lakh=100000. So "dhai sau"=250, \
"paune do sau"=175.
- "do" usually means the verb "give/send", not the number 2. It is only 2 \
when a unit or another number follows it.
- Payment mode: nakad/cash, UPI/PhonePe/GPay/online, udhaar/baaki/credit. \
"likh lena" and "khaate mein daal do" both mean udhaar.
- direction is "payment" when money is coming back to the shop \
(payment mila, jama kar diya, de diye), otherwise "sale".

If a field genuinely is not present in the message, return null for it \
rather than inventing a value. A missing amount is recoverable; a wrong one \
is money out of someone's pocket.\
"""


class ExtractedFields(BaseModel):
    """Schema the model must return. Validated before it reaches the ledger."""

    amount: float | None = Field(
        default=None, description="Total money for the transaction, or null."
    )
    mode: Literal["cash", "upi", "udhaar", "unknown"] = "unknown"
    direction: Literal["sale", "payment", "unknown"] = "unknown"
    items: list[str] = Field(default_factory=list)
    customer: str | None = Field(
        default=None, description="Name appearing in the text, or null."
    )
    note: str = Field(
        default="", description="One short line on anything ambiguous."
    )


class IndexedFields(ExtractedFields):
    """Batch variant. The index maps a result back to its input block."""

    index: int


class BatchResult(BaseModel):
    results: list[IndexedFields]


# --------------------------------------------------------------------------
# Prompt construction
# --------------------------------------------------------------------------

def _rules_summary(rules: RuleResult) -> str:
    """What the deterministic layer already settled."""
    lines = []
    if "amount" in rules.resolved:
        lines.append(f"- amount = {rules.amount} (resolved, do not change)")
    if "mode" in rules.resolved:
        lines.append(f"- mode = {rules.mode.value} (resolved, do not change)")
    if "items" in rules.resolved:
        labels = ", ".join(i.label() for i in rules.items)
        lines.append(f"- items = {labels} (resolved, do not change)")
    if rules.all_numbers:
        found = ", ".join(f"{v:g} (from '{m}')" for v, m in rules.all_numbers)
        lines.append(f"- numbers found in the text: {found}")
    return "\n".join(lines) or "- nothing resolved"


def _build_user_prompt(
    text: str,
    gaps: list[str],
    known_customers: list[str],
    rules: RuleResult,
    recent_items: list[str] | None = None,
) -> str:
    parts = [f"MESSAGE:\n{text}\n"]

    parts.append("ALREADY RESOLVED BY RULES:\n" + _rules_summary(rules) + "\n")

    if gaps:
        wanted = "\n".join(f"- {g}: {_FIELD_HELP.get(g, '')}" for g in gaps)
        parts.append(f"FILL ONLY THESE GAPS:\n{wanted}\n")

    if known_customers:
        parts.append(
            "THIS SHOP'S CUSTOMERS (match against these before inventing a new "
            f"name):\n{', '.join(known_customers)}\n"
        )

    if recent_items:
        parts.append(f"ITEMS THIS SHOP SELLS:\n{', '.join(recent_items)}\n")

    return "\n".join(parts)


# --------------------------------------------------------------------------
# The layer
# --------------------------------------------------------------------------

class ClaudeLayer:
    """
    Implements the `LLMLayer` protocol in pipeline.py.

    Construct it with no arguments; it resolves credentials the way the SDK
    does (ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN, or an `ant auth login`
    profile). `available()` reports whether it can actually run, so callers
    can degrade cleanly instead of raising on a shop counter.
    """

    def __init__(
        self,
        client=None,
        model: str = MODEL,
        corrections=None,
        recent_items: list[str] | None = None,
    ) -> None:
        self.model = model
        self.corrections = corrections
        self.recent_items = recent_items or []
        self._client = client
        self._init_error: str | None = None

        # Call accounting. Without this a credential or network failure is
        # invisible: the pipeline degrades to rules-only and every caller
        # happily reports "rules + Claude" numbers that Claude never touched.
        self.calls_ok = 0
        self.calls_failed = 0
        self.last_error: str | None = None

        if client is None:
            try:
                import anthropic

                self._client = anthropic.Anthropic()
            except Exception as exc:  # missing SDK
                self._init_error = str(exc)

    def available(self) -> bool:
        """
        Can this layer actually make a call?

        Constructing the client is NOT proof of anything -- `Anthropic()`
        succeeds with no credentials at all and only fails when a request is
        made. Check that a credential actually resolved.
        """
        if self._client is None:
            return False
        # An injected fake (tests) has no credential attributes; treat it as
        # available so tests do not need to stub them.
        if not hasattr(self._client, "api_key"):
            return True
        return bool(
            getattr(self._client, "api_key", None)
            or getattr(self._client, "auth_token", None)
        )

    # ------------------------------------------------------------------
    # Single message -- the LLMLayer protocol method
    # ------------------------------------------------------------------

    def extract(
        self,
        text: str,
        gaps: list[str],
        known_customers: list[str],
        rule_result: RuleResult,
    ) -> dict:
        if not self.available():
            return {}

        messages = self._few_shot_messages(text)
        messages.append({
            "role": "user",
            "content": _build_user_prompt(
                text, gaps, known_customers, rule_result, self.recent_items
            ),
        })

        # NOTE: not tuning `output_config.effort` here -- messages.parse()
        # already populates output_config.format, and mixing the two is
        # untested against this SDK version. Revisit once there is a key to
        # measure latency with.
        try:
            response = self._client.messages.parse(
                model=self.model,
                max_tokens=MAX_TOKENS,
                system=[{
                    "type": "text",
                    "text": SYSTEM_PROMPT,
                    # Stable prefix -- the system prompt and few-shots repeat
                    # on every extraction, so cache them.
                    "cache_control": {"type": "ephemeral"},
                }],
                messages=messages,
                output_format=ExtractedFields,
            )
        except Exception as exc:
            self.calls_failed += 1
            self.last_error = f"{type(exc).__name__}: {exc}"
            raise

        self.calls_ok += 1
        parsed = response.parsed_output
        if parsed is None:
            return {}
        return self._to_gap_dict(parsed, gaps)

    # ------------------------------------------------------------------
    # Bulk -- N blocks in ONE request
    # ------------------------------------------------------------------

    def extract_many(
        self,
        texts: list[str],
        known_customers: list[str],
        rule_results: list[RuleResult],
    ) -> list[dict]:
        """
        Batch path for bulk ingestion.

        Twenty blocks become one request instead of twenty. Results carry an
        explicit index so a short or reordered response cannot silently
        misalign entries with customers.
        """
        if not self.available() or not texts:
            return [{} for _ in texts]

        blocks = []
        for i, (text, rules) in enumerate(zip(texts, rule_results)):
            gaps = [f for f in ("amount", "mode", "items") if f not in rules.resolved]
            blocks.append(
                f"### BLOCK {i}\n"
                + _build_user_prompt(text, gaps, [], rules, self.recent_items)
            )

        prompt = (
            f"Extract every block below. Return one result per block, each "
            f"carrying its BLOCK index.\n\n"
            f"THIS SHOP'S CUSTOMERS:\n{', '.join(known_customers)}\n\n"
            + "\n\n".join(blocks)
        )

        response = self._client.messages.parse(
            model=self.model,
            max_tokens=MAX_TOKENS,
            system=[{
                "type": "text",
                "text": SYSTEM_PROMPT,
                "cache_control": {"type": "ephemeral"},
            }],
            messages=[{"role": "user", "content": prompt}],
            output_format=BatchResult,
        )

        parsed = response.parsed_output
        out: list[dict] = [{} for _ in texts]
        if parsed is None:
            return out

        for result in parsed.results:
            if 0 <= result.index < len(texts):
                gaps = [
                    f for f in ("amount", "mode", "items")
                    if f not in rule_results[result.index].resolved
                ]
                out[result.index] = self._to_gap_dict(result, gaps)
        return out

    # ------------------------------------------------------------------

    @staticmethod
    def _to_gap_dict(parsed: ExtractedFields, gaps: list[str]) -> dict:
        """
        Return only what the pipeline asked about.

        The amount is always included even when the rules resolved it -- the
        pipeline uses agreement between the two layers as a confidence
        signal, and it cannot compare what it never receives.
        """
        result: dict = {}
        if parsed.amount is not None:
            result["amount"] = parsed.amount
        if "mode" in gaps and parsed.mode != "unknown":
            result["mode"] = parsed.mode
        if "items" in gaps and parsed.items:
            result["items"] = parsed.items
        if parsed.customer:
            result["customer"] = parsed.customer
        if parsed.direction != "unknown":
            result["direction"] = parsed.direction
        if parsed.note:
            result["note"] = parsed.note
        return result

    def _few_shot_messages(self, text: str) -> list[dict]:
        """Past corrections from this shop, as worked examples."""
        if self.corrections is None:
            return []

        messages: list[dict] = []
        for example in self.corrections.similar(text, limit=3):
            messages.append({"role": "user", "content": f"MESSAGE:\n{example.raw_text}"})
            messages.append({"role": "assistant", "content": example.as_json()})
        return messages
