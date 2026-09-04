"""
Conversation-level order resolution.

`segment()` in whatsapp.py groups CONSECUTIVE messages from ONE sender. That
is the right unit when a shopkeeper dictates an order to himself, and the
wrong one for how customers actually order:

    customer   is there an availability of blue kurtis
    shopkeeper yes it is available, 500 ka
    customer   mujhe 3 kharidne hai

Nothing in the last message names an item, and nothing in the first names a
quantity. Segmented per sender, the item and the quantity land in different
blocks and both are lost. The order only exists across the turns.

So this module threads the two sides together and resolves the reference:
the item comes from whichever turn last named one, the quantity from the turn
that committed, and the rate from whatever the shopkeeper quoted.

THREE RULES DECIDE WHETHER AN ORDER EXISTS AT ALL.

**1. Only a customer can place an order.** The shopkeeper naming a price is
quoting, not buying. Without this, "500 ka" from the shop books a sale to
itself every time someone asks what something costs.

**2. Asking is not buying.** "Is blue kurti available?" establishes what is
being discussed and nothing more. An order needs a commitment -- chahiye,
kharidna hai, bhej do, I want, send me. A shop whose khata fills up with
every browsing question is a khata nobody reads.

**3. A resolved order carries its evidence.** `evidence` holds the actual
messages it came from, so the shopkeeper confirming it can see the words the
customer used. `canonical_text` is the tidied form the extraction engine
reads. They are kept apart on purpose -- showing someone a sentence they
never received, as proof of what they owe, is how you lose an argument about
money.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from .categories import Category, units_for
from .numbers import find_numbers, normalise
from .rules import _UNITS, LineItem, PaymentMode, apply_rules, detect_mode

# --------------------------------------------------------------------------
# Commitment
# --------------------------------------------------------------------------
#
# Tuned for PRECISION, unlike the classifier in whatsapp.py which is tuned
# for recall. A false positive here does not add a card to a review queue --
# it invents an order out of a question, attaches the last item anyone
# mentioned, and asks the shopkeeper to confirm a sale that never happened.

_COMMIT_PATTERNS = [
    # Hinglish -- wanting
    r"\bchah?iye\b", r"\bchaeye\b", r"\bchaahiye\b",
    r"\bkharid(?:na|ne|ni|unga|enge)\b",
    r"\b(?:le|lena)\s+(?:lunga|loonga|lungi|hai|hain)\b",
    r"\b(?:lunga|loonga|lungi|lenge)\b",
    r"\bmangwa(?:na|do|dena)\b",
    r"\border\s+(?:karna|kar|karni|kardo|kar\s+do)\b",
    # Hinglish -- instructing
    r"\bbhej\s*(?:do|dena|dijiye|de)\b", r"\bbhejo\b", r"\bbhejna\b",
    r"\bde\s*(?:do|dena|dijiye)\b", r"\bdedo\b",
    r"\bpack\s+kar\b", r"\bnikal\s*(?:do|dena)\b",
    # English
    r"\bi\s+(?:want|need|will\s+take|'ll\s+take|ll\s+take)\b",
    r"\bwant\s+to\s+(?:buy|order|take)\b",
    r"\b(?:send|give|deliver)\s+me\b",
    r"\bplace\s+an?\s+order\b", r"\bi\s+am\s+ordering\b",
    r"\bbook\s+(?:it|them|me)\b",
    # Devanagari -- \b is unusable here, see rules.DEVA_START
    r"(?<!\S)चाहिए", r"(?<!\S)खरीद\w*", r"(?<!\S)भेज\s*(?:दो|दीजिए|देना)",
    r"(?<!\S)दे\s*दो", r"(?<!\S)लूंगा", r"(?<!\S)लेना\s+है",
]

# Questions. These set the topic and must never book anything on their own.
_ENQUIRY_PATTERNS = [
    r"\bavailab(?:le|ility)\b", r"\bstock\s+(?:hai|me|mein)\b",
    r"\bhai\s+kya\b", r"\bmilega\b", r"\bmilegi\b", r"\bhoga\b",
    r"\bkitne?\s+ka\b", r"\bkitna\b", r"\bprice\b", r"\brate\s+kya\b",
    r"\bhow\s+much\b", r"\bdo\s+you\s+have\b", r"\bis\s+there\b",
    r"(?<!\S)है\s*क्या", r"(?<!\S)कितने?\s+का", r"(?<!\S)मिलेगा",
]

_COMMIT_RE = [re.compile(p) for p in _COMMIT_PATTERNS]
_ENQUIRY_RE = [re.compile(p) for p in _ENQUIRY_PATTERNS]


def is_commitment(text: str) -> bool:
    """Did this turn actually order something?"""
    norm = normalise(text)
    return any(r.search(norm) for r in _COMMIT_RE)


def is_enquiry(text: str) -> bool:
    """Is this a question about availability or price?"""
    norm = normalise(text)
    return any(r.search(norm) for r in _ENQUIRY_RE)


# --------------------------------------------------------------------------
# Turns and threads
# --------------------------------------------------------------------------

@dataclass
class Turn:
    sender: str
    body: str
    timestamp: datetime | None = None
    is_shopkeeper: bool = False
    content_hash: str = ""

    @property
    def is_customer(self) -> bool:
        return not self.is_shopkeeper


@dataclass
class Thread:
    """
    One continuous exchange.

    A thread ends when the chat goes quiet for longer than the gap. Two
    orders a week apart must not share a topic -- otherwise "3 chahiye" on
    Friday attaches to the kurti someone asked about on Monday.
    """

    customer: str
    turns: list[Turn] = field(default_factory=list)

    @property
    def started_at(self) -> datetime | None:
        stamps = [t.timestamp for t in self.turns if t.timestamp]
        return min(stamps) if stamps else None

    @property
    def ended_at(self) -> datetime | None:
        stamps = [t.timestamp for t in self.turns if t.timestamp]
        return max(stamps) if stamps else None

    @property
    def hashes(self) -> list[str]:
        return [t.content_hash for t in self.turns if t.content_hash]


def build_threads(
    turns: list[Turn],
    customer: str,
    gap_minutes: int = 45,
) -> list[Thread]:
    """
    Split a chat into threads on conversational silence.

    Turns are sorted by timestamp first. Capture order is not chronological
    order -- a scroll-harvest walks the screen upward, and an export can
    interleave when a phone's clock has been corrected mid-history.
    """
    ordered = sorted(
        turns,
        key=lambda t: t.timestamp or datetime.min,
    )

    threads: list[Thread] = []
    gap = timedelta(minutes=gap_minutes)

    for turn in ordered:
        if threads:
            previous = threads[-1].turns[-1]
            both_stamped = previous.timestamp and turn.timestamp
            within = not both_stamped or (turn.timestamp - previous.timestamp) <= gap
            if within:
                threads[-1].turns.append(turn)
                continue
        threads.append(Thread(customer=customer, turns=[turn]))

    return threads


# --------------------------------------------------------------------------
# Resolution
# --------------------------------------------------------------------------

@dataclass
class ResolvedOrder:
    customer: str
    items: list[LineItem]
    amount: float | None
    mode: str
    #: Canonical utterance, fed to the extraction engine.
    canonical_text: str
    #: The real messages, shown to the shopkeeper.
    evidence: str
    occurred_at: datetime | None
    content_hashes: list[str] = field(default_factory=list)
    signals: list[str] = field(default_factory=list)
    #: True when the item came from an EARLIER turn than the commitment --
    #: the case segmentation alone cannot handle.
    carried_topic: bool = False


def _bare_quantity(
    text: str,
    category: Category | str | None,
) -> tuple[float | None, str | None]:
    """
    A quantity with no item attached: "mujhe 3 chahiye", "I want 3 pieces".

    Returns (quantity, unit). The unit is optional -- "3 chahiye" is three of
    whatever is being discussed, and refusing it because no unit was written
    would drop the most common way a customer commits.
    """
    units = {**_UNITS, **units_for(category)}
    norm = normalise(text)
    tokens = norm.split()

    numbers = find_numbers(text)
    if not numbers:
        return None, None

    # The first number is the count. A commitment rarely carries two.
    value, matched = numbers[0]

    # Look for a unit straight after the matched number run.
    unit = None
    tail = matched.split()[-1] if matched.split() else matched
    for i, tok in enumerate(tokens):
        if tok.strip(".") == tail and i + 1 < len(tokens):
            candidate = tokens[i + 1].strip(".")
            if candidate in units:
                unit = units[candidate]
            break

    return value, unit


def resolve(
    thread: Thread,
    category: Category | str | None = None,
) -> ResolvedOrder | None:
    """
    Turn one thread into at most one order.

    Walks the turns in time order keeping two pieces of state: the item most
    recently named by EITHER side, and the rate the shopkeeper last quoted.
    A customer commitment then binds them together.

    Returns None when the thread never committed to anything, which is the
    common case and the point -- most chat is not an order.
    """
    if not thread.turns:
        return None

    topic: LineItem | None = None
    topic_turn: Turn | None = None
    quoted_rate: float | None = None
    commit_turn: Turn | None = None
    commit_rules = None
    signals: list[str] = []

    for turn in thread.turns:
        rules = apply_rules(turn.body, category)

        # Whoever names an item sets the topic -- the shopkeeper answering
        # "blue kurti hai, 500 ka" names it just as usefully as the customer
        # asking for it.
        if rules.items:
            topic = rules.items[-1]
            topic_turn = turn
            if topic.unit_price is not None:
                quoted_rate = topic.unit_price

        # A price the shopkeeper states is the rate on offer.
        if turn.is_shopkeeper and rules.amount is not None:
            quoted_rate = rules.amount
            signals.append("shop-quoted-rate")

        if turn.is_customer and is_commitment(turn.body):
            commit_turn = turn
            commit_rules = rules

    if commit_turn is None or commit_rules is None:
        return None
    if topic is None:
        # Committed to something we cannot name. Better to drop it than to
        # book a sale of "?" -- an unnamed line in a khata is not evidence of
        # anything and cannot be checked later.
        return None

    signals.append("customer-commitment")

    # ---- the item -------------------------------------------------------
    # An item named in the commitment itself wins; otherwise carry the topic
    # forward. That carry is the whole reason this module exists.
    if commit_rules.items:
        item = commit_rules.items[-1]
        carried = False
    else:
        item = LineItem(
            name=topic.name,
            modifier=topic.modifier,
            unit=topic.unit,
        )
        carried = True
        signals.append("carried-topic")

    # ---- the quantity ---------------------------------------------------
    if item.quantity is None:
        quantity, unit = _bare_quantity(commit_turn.body, category)
        if quantity is not None:
            item.quantity = quantity
            item.unit = unit or item.unit
            signals.append("quantity-from-commitment")

    # ---- the rate -------------------------------------------------------
    if item.unit_price is None and quoted_rate is not None:
        # Only apply a quoted rate as a PER-UNIT price when the quantity is
        # known. Otherwise the rate is just the amount, and multiplying by a
        # quantity we guessed is how a Rs 500 sale becomes Rs 1500.
        if item.quantity is not None:
            item.unit_price = quoted_rate
            signals.append("rate-from-quote")

    # ---- mode -----------------------------------------------------------
    mode = PaymentMode.UNKNOWN.value
    for turn in thread.turns:
        detected, _ = detect_mode(turn.body)
        if detected is not PaymentMode.UNKNOWN:
            mode = detected.value

    # ---- canonical form -------------------------------------------------
    #
    # Rebuilt in the order the rules parser expects -- quantity, unit,
    # modifier, noun -- rather than by pasting the turns together. Pasted raw,
    # "blue kurti" + "mujhe 3 kharidne hai" puts the 3 AFTER the noun with a
    # word between, where it reads as three rupees rather than three kurtis.
    parts: list[str] = []
    if item.quantity is not None:
        qty = int(item.quantity) if item.quantity == int(item.quantity) else item.quantity
        parts.append(str(qty))
        if item.unit:
            parts.append(item.unit)
    if item.modifier:
        parts.append(item.modifier)
    parts.append(item.name)
    if item.unit_price is not None:
        rate = int(item.unit_price) if item.unit_price == int(item.unit_price) else item.unit_price
        parts.extend([str(rate), "each"])
    if mode != PaymentMode.UNKNOWN.value:
        parts.append(mode)

    canonical = " ".join(parts)

    evidence = "\n".join(
        f"{t.sender}: {t.body}"
        for t in thread.turns
        if t in (topic_turn, commit_turn) or apply_rules(t.body, category).amount is not None
    )

    amount = item.line_total
    if amount is None and item.quantity is None:
        amount = quoted_rate

    return ResolvedOrder(
        customer=thread.customer,
        items=[item],
        amount=amount,
        mode=mode,
        canonical_text=canonical,
        evidence=evidence or f"{commit_turn.sender}: {commit_turn.body}",
        occurred_at=commit_turn.timestamp,
        content_hashes=thread.hashes,
        signals=signals,
        carried_topic=carried,
    )


def resolve_all(
    turns: list[Turn],
    customer: str,
    category: Category | str | None = None,
    gap_minutes: int = 45,
) -> list[ResolvedOrder]:
    """Thread a whole chat and resolve every order in it."""
    return [
        order
        for thread in build_threads(turns, customer, gap_minutes)
        if (order := resolve(thread, category)) is not None
    ]
