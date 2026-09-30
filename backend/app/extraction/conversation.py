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
from .rules import _UNITS, Direction, LineItem, PaymentMode, apply_rules, detect_mode

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
    #: "sale" or "payment". A payment settles an existing balance and carries
    #: no items; without this the lane could only ever book sales, so a
    #: customer texting "i have completed the payment" left their bill
    #: standing as outstanding for ever.
    direction: str = "sale"
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


def _measure_quantity(
    text: str,
    category: Category | str | None,
) -> float | None:
    """
    The quantity this message attaches to a unit: 2 in "2kg", 1 in "500 for 1kg".

    Returns None when no measure is stated at all. Looks for a number FOLLOWED
    BY A UNIT rather than any number, because a bare number in a price quote
    is the price itself.

    The SIZE matters, not merely the presence, which is the distinction that
    decides whether a quote is a rate or a bundle -- see the caller.
    """
    from .numbers import is_number_token, parse_number_phrase
    from .numbers import tokenize as _tokenize

    units = {**_UNITS, **units_for(category)}
    tokens = _tokenize(text)

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
                return value
        i = max(j, i + 1)
    return None


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

    # Keyed by (modifier, name) and insertion-ordered, so "yellow kurti" and
    # "blue kurti" are two lines while two mentions of either are one.
    topics: dict[tuple[str, str], LineItem] = {}
    topic_turn: Turn | None = None
    payment_turn: Turn | None = None
    payment_rules = None
    quoted_rate: float | None = None
    quoted_total: float | None = None
    quoted_total_qty: float | None = None
    shop_run_qty: float | None = None
    previous_speaker_was_shop: bool | None = None
    commit_turn: Turn | None = None
    commit_rules = None
    signals: list[str] = []

    for turn in thread.turns:
        rules = apply_rules(turn.body, category)

        # A customer saying the money has arrived settles a balance. Checked
        # before anything else, because a payment turn has no items and the
        # item machinery below would otherwise throw the whole thread away.
        if turn.is_customer and rules.direction is Direction.PAYMENT:
            payment_turn = turn
            payment_rules = rules

        # Whoever names an item sets the topic -- the shopkeeper answering
        # "blue kurti hai, 500 ka" names it just as usefully as the customer
        # asking for it.
        for named in rules.items:
            # A later mention of the SAME item must not erase what an earlier
            # one established. Real chat names a thing repeatedly, and the
            # barest mention is often the last:
            #
            #     "2kg tamatar chaiye" ... "organic grown tamatar"
            #
            # Same item name means merge, keeping whichever mention actually
            # stated each field. DIFFERENT names accumulate rather than
            # replace -- "8 yellow kurtis and 2 blue kurtis" is one order for
            # two things, and keeping only the last of them silently dropped
            # four thousand rupees of yellow kurtis off a real bill.
            key = (named.modifier or "", named.name)
            existing = topics.get(key)
            if existing is not None:
                topics[key] = LineItem(
                    name=named.name,
                    quantity=named.quantity if named.quantity is not None else existing.quantity,
                    unit=named.unit or existing.unit,
                    modifier=named.modifier or existing.modifier,
                    unit_price=named.unit_price
                    if named.unit_price is not None
                    else existing.unit_price,
                )
            else:
                topics[key] = named
            topic_turn = turn
            if named.unit_price is not None:
                quoted_rate = named.unit_price

        # A price the shopkeeper states. Whether it is a RATE or a TOTAL is
        # genuinely ambiguous in chat, and getting it backwards multiplies or
        # divides someone's bill:
        #
        #     "blue kurti?"  "500 ka"        -> 500 EACH,  x3 = 1500
        #     "500 for 1kg"                  -> 500 PER KG, x2 = 1000
        #     "2kg for"      "500 only"      -> 500 TOTAL for the 2kg
        #
        # What separates them is the SIZE of the quantity in the quote, not
        # merely whether one is present. Quoting a price against ONE of
        # something states a unit rate; quoting it against several prices
        # that bundle; quoting it against nothing answers "what does one
        # cost". An earlier version only asked whether a measure existed, so
        # "500 for 1kg" was read as a bundle and two kilos of aloo were
        # booked at Rs 500 instead of Rs 1000.
        #
        # Runs rather than single messages, because a quote routinely arrives
        # split: "2kg for" then "500 only".
        if turn.is_shopkeeper:
            if previous_speaker_was_shop is False:
                shop_run_qty = None
            stated = _measure_quantity(turn.body, category)
            if stated is None and rules.items and rules.items[-1].quantity is not None:
                stated = rules.items[-1].quantity
            if stated is not None:
                shop_run_qty = stated

            if rules.amount is not None:
                if shop_run_qty is not None and shop_run_qty > 1:
                    quoted_total = rules.amount
                    quoted_total_qty = shop_run_qty
                    signals.append("shop-quoted-total")
                else:
                    quoted_rate = rules.amount
                    signals.append("shop-quoted-rate")
        previous_speaker_was_shop = turn.is_shopkeeper

        if turn.is_customer and is_commitment(turn.body):
            commit_turn = turn
            commit_rules = rules

    # ---- a payment settles a balance ------------------------------------
    #
    # Emitted before the sale path and independently of it: a payment needs no
    # item, no commitment and no topic, so every guard below would throw it
    # away. The amount is whatever the message stated; when it stated none,
    # None is returned rather than a guess, and the review card asks. Guessing
    # here would clear a debt by an amount nobody agreed.
    if payment_turn is not None and payment_rules is not None:
        paid_mode = PaymentMode.UNKNOWN.value
        for turn in thread.turns:
            detected, _ = detect_mode(turn.body)
            if detected is not PaymentMode.UNKNOWN:
                paid_mode = detected.value

        pay_signals = list(dict.fromkeys(signals + ["customer-paid"]))
        return ResolvedOrder(
            customer=thread.customer,
            items=[],
            amount=payment_rules.amount,
            mode=paid_mode if paid_mode != PaymentMode.UNKNOWN.value else "cash",
            direction="payment",
            canonical_text=payment_turn.body,
            evidence=f"{payment_turn.sender}: {payment_turn.body}",
            occurred_at=payment_turn.timestamp,
            content_hashes=thread.hashes,
            signals=pay_signals,
            carried_topic=False,
        )

    if commit_turn is None or commit_rules is None:
        return None
    if not topics:
        # Committed to something we cannot name. Better to drop it than to
        # book a sale of "?" -- an unnamed line in a khata is not evidence of
        # anything and cannot be checked later.
        return None

    signals = list(dict.fromkeys(signals))
    signals.append("customer-commitment")

    # ---- the items ------------------------------------------------------
    #
    # Items named in the commitment itself win; otherwise carry the topic
    # forward. That carry is the whole reason this module exists.
    #
    # ALL of them, not the last. "8 yellow kurtis and 2 blue kurtis" is one
    # order for two things, and keeping only the last dropped Rs 4,000 of
    # yellow kurtis off a real bill -- which only surfaced because the
    # customer happened to restate it two minutes later.
    if commit_rules.items:
        items = [
            LineItem(
                name=i.name, quantity=i.quantity, unit=i.unit,
                modifier=i.modifier, unit_price=i.unit_price,
            )
            for i in commit_rules.items
        ]
        carried = False
    else:
        # Deliberately WITHOUT the quantity. The commitment gets first claim
        # on it below, and only what the commitment leaves unanswered falls
        # back to the topic. Copying it here instead let a number the topic
        # never really stated win: "do you have red saree" parses "do" as the
        # Hindi two, so the topic carries a phantom quantity of 2 that
        # "i want 3 pieces" must be able to overrule.
        items = [
            LineItem(
                name=t.name, quantity=None, unit=t.unit,
                modifier=t.modifier, unit_price=t.unit_price,
            )
            for t in topics.values()
        ]
        carried = True
        signals.append("carried-topic")

    # ---- the quantity ---------------------------------------------------
    #
    # A bare number in the commitment can only be attached when there is ONE
    # thing it could belong to. "2 chahiye" against a single topic is two of
    # that; against "yellow kurti and blue kurti" it is genuinely ambiguous,
    # and splitting or duplicating it would invent a quantity.
    if len(items) == 1 and items[0].quantity is None:
        quantity, unit = _bare_quantity(commit_turn.body, category)
        if quantity is not None:
            items[0].quantity = quantity
            items[0].unit = unit or items[0].unit
            signals.append("quantity-from-commitment")
        elif carried:
            established = next(iter(topics.values()))
            if established.quantity is not None:
                items[0].quantity = established.quantity
                items[0].unit = established.unit or items[0].unit
                signals.append("quantity-from-topic")

    # Anything the commitment named without a quantity, but which the thread
    # established one for earlier, takes it back.
    for item in items:
        if item.quantity is None:
            established = topics.get((item.modifier or "", item.name))
            if established is not None and established.quantity is not None:
                item.quantity = established.quantity
                item.unit = established.unit or item.unit
                signals.append("quantity-from-topic")

    # ---- the rate -------------------------------------------------------
    # Only as a PER-UNIT price where the quantity is known; multiplying by a
    # quantity we guessed is how a Rs 500 sale becomes Rs 1500.
    for item in items:
        if item.unit_price is None and quoted_rate is not None and item.quantity is not None:
            item.unit_price = quoted_rate
            if "rate-from-quote" not in signals:
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
    def _phrase(item: LineItem) -> str:
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
            rate = (
                int(item.unit_price)
                if item.unit_price == int(item.unit_price)
                else item.unit_price
            )
            parts.extend([str(rate), "each"])
        return " ".join(parts)

    canonical = " aur ".join(_phrase(i) for i in items)
    if mode != PaymentMode.UNKNOWN.value:
        canonical = f"{canonical} {mode}"

    evidence = "\n".join(
        f"{t.sender}: {t.body}"
        for t in thread.turns
        if t in (topic_turn, commit_turn) or apply_rules(t.body, category).amount is not None
    )

    # A quoted TOTAL is the amount outright -- multiplying it by the quantity
    # it already covers is how a Rs 500 sale becomes Rs 1000.
    line_totals = [i.line_total for i in items if i.line_total is not None]
    amount = round(sum(line_totals), 2) if len(line_totals) == len(items) and items else None

    if quoted_total is not None and amount is None:
        amount = quoted_total
        if len(items) == 1:
            only = items[0]
            if (
                only.quantity is not None
                and quoted_total_qty is not None
                and abs(only.quantity - quoted_total_qty) > 1e-9
            ):
                signals.append("bundle-quantity-mismatch")
    elif amount is None and all(i.quantity is None for i in items):
        amount = quoted_rate

    return ResolvedOrder(
        customer=thread.customer,
        items=items,
        amount=amount,
        mode=mode,
        direction="sale",
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
