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
    r"\bbhijwa\s*(?:do|dena|dijiye|de)\b", r"\bbhejiye\b", r"\bbhej\s+dijie\b",
    r"\bdijiye\b", r"\brakh\s*(?:do|dena|dijiye|lena)\b", r"\bmangwa\w*\s+(?:do|dena|hai)\b",
    r"\b(?:le|lena)\s+(?:aana|ana|aao|jaunga|jayenge)\b",
    # Writing it down IS the order for a regular customer.
    r"\blikh\s*(?:lo|lena|do|dena|dijiye|lijiye)\b", r"\bjod\s*(?:do|dena|dijiye)\b",
    r"\badd\s+kar\w*\b",
    # English
    r"\bi\s+(?:want|need|will\s+take|'ll\s+take|ll\s+take)\b",
    r"\bwant\s+to\s+(?:buy|order|take)\b",
    r"\b(?:send|give|deliver)\s+me\b",
    r"\b(?:please|pls|plz|kindly)\s+(?:send|deliver|give|bhej\w*|de\s*do)\b",
    r"^\s*send\b", r"\bi\s+(?:would\s+like|require)\b",
    r"\bplace\s+an?\s+order\b", r"\bi\s+am\s+ordering\b",
    r"\bbook\s+(?:it|them|me)\b",
    # Devanagari -- \b is unusable here, see rules.DEVA_START
    r"(?<!\S)चाहिए", r"(?<!\S)खरीद\w*", r"(?<!\S)भेज\s*(?:दो|दीजिए|देना)",
    r"(?<!\S)दे\s*दो", r"(?<!\S)लूंगा", r"(?<!\S)लेना\s+है",
    r"(?<!\S)भेजना", r"(?<!\S)भेजिए", r"(?<!\S)भिजवा", r"(?<!\S)दीजिए",
    r"(?<!\S)लिख\s*(?:लो|लेना|दो|देना)", r"(?<!\S)रख\s*(?:दो|देना)",
]

# Questions. These set the topic and must never book anything on their own.
_ENQUIRY_PATTERNS = [
    r"\bavailab(?:le|ility)\b", r"\bstock\s+(?:hai|me|mein)\b",
    r"\bhai\s+kya\b", r"\bmilega\b", r"\bmilegi\b", r"\bhoga\b",
    r"\bkitne?\s+ka\b", r"\bkitna\b", r"\bprice\b", r"\brate\s+kya\b",
    r"\bhow\s+much\b", r"\bdo\s+you\s+have\b", r"\bis\s+there\b",
    r"(?<!\S)है\s*क्या", r"(?<!\S)कितने?\s+का", r"(?<!\S)मिलेगा",
    r"\bkya\s+(?:aapke|apke|tumhare)\s+paas\b", r"\bkaun\s*s[aei]\b", r"\bkaunse\b",
    r"\bkitne?\s+k[ie]\b", r"\bkya\s+rate\b", r"\bkab\s+tak\b",
]

# "Nahi chahiye" contains "chahiye". A cancellation must never read as the
# order it cancels.
_NEGATION_PATTERNS = [
    r"\bnah?i+n?\s+chah?iye\b", r"\bmat\s+(?:bhej|de|la|bana)\w*", r"\bcancel\w*\b",
    r"\bno\s+need\b", r"\bdon'?t\s+(?:send|need|want)\b", r"\bnot\s+(?:needed|required)\b",
    r"\bzarurat\s+nahi\b", r"(?<!\S)नहीं\s+चाहिए", r"(?<!\S)मत\s+भेज",
]

# A message about goods that ALREADY moved -- a complaint, a return, a
# chase. "kal 3 kg aata bheja tha wo kharab tha" names an item and a
# quantity, and is the opposite of an order.
_PAST_PATTERNS = [
    r"\b(?:tha|thi|thaa)\b", r"\bkharaa?b\b", r"\bwapa?s\b", r"\breturn\w*\b",
    r"\bexchange\b", r"\bbadal\w*\b", r"\bnah?i\s+(?:aay?[ai]|mila|mili|pahunch\w*)\b",
    r"\bgalat\b", r"\bdamage\w*\b", r"\bexpire\w*\b", r"\bkam\s+(?:tha|thi|aaya|aayi|nikla)\b",
    r"\bmangwa(?:ya|yi|ye)\b", r"\bliya\s+tha\b",
    r"(?<!\S)थ[ाीे](?!\S)", r"(?<!\S)खराब", r"(?<!\S)वापस",
]

# What a shopkeeper says to announce a TOTAL rather than a price for one:
# "250 hua", "130 hua total", "4300 hoga", "done, 150".
_TOTAL_PATTERNS = [
    r"\btotal\b", r"\bhu[ae]\b", r"\bhui\b", r"\bho\s+gay[aei]\b", r"\bbill\b", r"\bkul\b",
    r"\bhog[ae]\b", r"\bbant[ae]\b", r"\bbanega\b", r"\blagenge?\b", r"\blagega\b",
    r"\bdone\b", r"\bsab\s+mila\s*ke\b", r"\bmilakar\b",
    r"(?<!\S)हुआ", r"(?<!\S)हुए", r"(?<!\S)कुल", r"(?<!\S)टोटल", r"(?<!\S)होगा",
]
# ...and a price for ONE: "450 ki ek", "10 ka ek", "500 each".
_RATE_PATTERNS = [
    r"\beach\b", r"\bper\b", r"\bprati\b", r"\b(?:ka|ki|ke)\s+(?:ek|1)\b",
    r"\b(?:ek|1)\s+(?:ka|ki|ke)\b", r"\bpiece\b", r"\bonwards\b", r"\bse\s+shuru\b",
    r"(?<!\S)प्रति", r"(?<!\S)(?:का|की)\s+एक",
]

# Saying "write it down" on an amount with nothing named is still a khata
# entry: "Rs.250/- ka saman likh lo".
_CREDIT_ENTRY = re.compile(r"\blikh\w*|\budhaa?r\w*|\bkhaate?\b|(?<!\S)उधार|(?<!\S)लिख")

_COMMIT_RE = [re.compile(p) for p in _COMMIT_PATTERNS]
_ENQUIRY_RE = [re.compile(p) for p in _ENQUIRY_PATTERNS]
_NEGATION_RE = [re.compile(p) for p in _NEGATION_PATTERNS]
_PAST_RE = [re.compile(p) for p in _PAST_PATTERNS]
_TOTAL_RE = [re.compile(p) for p in _TOTAL_PATTERNS]
_RATE_RE = [re.compile(p) for p in _RATE_PATTERNS]


def is_commitment(text: str) -> bool:
    """Did this turn actually order something?"""
    norm = normalise(text)
    if any(r.search(norm) for r in _NEGATION_RE):
        return False
    return any(r.search(norm) for r in _COMMIT_RE)


def is_enquiry(text: str) -> bool:
    """Is this a question about availability or price?"""
    if "?" in text:
        return True
    norm = normalise(text)
    return any(r.search(norm) for r in _ENQUIRY_RE)


def is_implicit_order(text: str, rules) -> bool:
    """
    An order with no ordering verb: a list of things with quantities.

    "2 packet parle g, 1 kurkure" and "5 kg aata aur 1 litre tel" are how
    most WhatsApp orders to a kirana actually arrive -- no chahiye, no bhej
    do, because sending a list to a shop already means "send this". Requiring
    a verb dropped them all.

    Precision is kept by what this refuses: a question, a cancellation, a
    complaint about goods already delivered, and a payment. Those are the
    messages that also name items with quantities and are not orders.
    """
    if not any(i.quantity is not None for i in rules.items):
        return False
    if rules.direction is Direction.PAYMENT or is_enquiry(text):
        return False
    norm = normalise(text)
    if any(r.search(norm) for r in _NEGATION_RE):
        return False
    return not any(r.search(norm) for r in _PAST_RE)


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


def _merge_item(store: dict[tuple[str, str], LineItem], named: LineItem) -> tuple[str, str]:
    """
    Add a mention to a running set of items, merging repeats.

    The same name merges, keeping whichever mention stated each field.
    DIFFERENT names accumulate rather than replace -- "8 yellow kurtis and 2
    blue kurtis" is one order for two things, and keeping only the last of
    them silently dropped four thousand rupees of yellow kurtis off a real
    bill. A mention WITHOUT a modifier merges into the single one that has
    one, and the other way round: "2kg tamatar chaiye" then "organic grown
    tamatar" is one line of organic tomatoes, not two.
    """
    key = (named.modifier or "", named.name)
    if key not in store:
        same = [k for k in store if k[1] == named.name and (not k[0] or not key[0])]
        if len(same) == 1:
            old = same[0]
            existing = store.pop(old)
            key = key if key[0] else old
            store[key] = _merged(existing, named)
            return key
        store[key] = LineItem(
            name=named.name, quantity=named.quantity, unit=named.unit,
            modifier=named.modifier, unit_price=named.unit_price,
        )
        return key
    store[key] = _merged(store[key], named)
    return key


def _merged(existing: LineItem, named: LineItem) -> LineItem:
    return LineItem(
        name=named.name,
        quantity=named.quantity if named.quantity is not None else existing.quantity,
        unit=named.unit or existing.unit,
        modifier=named.modifier or existing.modifier,
        unit_price=named.unit_price if named.unit_price is not None else existing.unit_price,
    )


def _shop_unit_rate(text: str, category: Category | str | None) -> float | None:
    """
    "400 kilo", "120 kilo" -- a shopkeeper's price PER UNIT, said the short way.

    Read as a measure it is four hundred kilos of paneer. One number, followed
    by a unit, large enough that nobody is quoting that much stock: that is
    a rate, and the khata multiplies it by what the customer takes.
    """
    numbers = find_numbers(text)
    if len(numbers) != 1:
        return None
    value, matched = numbers[0]
    units = {**_UNITS, **units_for(category)}
    tokens = normalise(text).split()
    tail = matched.split()[-1]
    for k, tok in enumerate(tokens):
        if tok.strip(".") == tail and k + 1 < len(tokens):
            if tokens[k + 1].strip(".") in units and value >= 20:
                return value
            return None
    return None


def resolve_thread(
    thread: Thread,
    category: Category | str | None = None,
) -> list[ResolvedOrder]:
    """
    Turn one thread into its orders: at most one sale and one payment.

    Walks the turns in time order keeping the items named so far, the items
    the customer has committed to, and what the shopkeeper quoted -- and
    WHEN they quoted it, because that decides what the number means. A
    customer commitment then binds them together.

    Returns an empty list when the thread never committed to anything, which
    is the common case and the point -- most chat is not an order.
    """
    if not thread.turns:
        return []

    # Keyed by (modifier, name) and insertion-ordered, so "yellow kurti" and
    # "blue kurti" are two lines while two mentions of either are one.
    topics: dict[tuple[str, str], LineItem] = {}
    ordered: dict[tuple[str, str], LineItem] = {}
    topic_turn: Turn | None = None
    payment_turn: Turn | None = None
    payment_rules = None
    payment_index = -1
    quoted_rate: float | None = None
    quoted_total: float | None = None
    quoted_total_qty: float | None = None
    shop_total: float | None = None
    shop_total_index = -1
    customer_amount: float | None = None
    shop_run_qty: float | None = None
    previous_speaker_was_shop: bool | None = None
    commit_turn: Turn | None = None
    commit_rules = None
    commit_index = -1
    last_item_commit_index = -1
    credit_entry: tuple[Turn, float] | None = None
    signals: list[str] = []

    for index, turn in enumerate(thread.turns):
        rules = apply_rules(turn.body, category)

        # A customer saying the money has arrived settles a balance -- or, if
        # it follows an order, says how that order was paid. Which one is
        # decided after the walk, once the order is known.
        if turn.is_customer and rules.direction is Direction.PAYMENT:
            payment_turn, payment_rules, payment_index = turn, rules, index

        # Whoever names an item sets the topic -- the shopkeeper answering
        # "blue kurti hai, 500 ka" names it just as usefully as the customer
        # asking for it.
        for named in rules.items:
            _merge_item(topics, named)
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
        #     "2 kilo chawal aur chai" "250 hua" -> 250 TOTAL for the order
        #
        # In that order of strength: what the shop SAYS ("hua", "total",
        # "each", "ki ek"), then the size of any quantity in the quote, then
        # WHEN it was said. A price stated before the customer has ordered
        # answers "what does it cost"; a bare number stated after an order
        # answers "what do I owe". Reading the second as a rate multiplied
        # one real bill of Rs 60 for two strips of tablets to Rs 4,22,500.
        if turn.is_shopkeeper:
            if previous_speaker_was_shop is False:
                shop_run_qty = None
            unit_rate = _shop_unit_rate(turn.body, category)
            if unit_rate is not None:
                quoted_rate = unit_rate
                signals.append("shop-quoted-rate")
            else:
                stated = _measure_quantity(turn.body, category)
                if stated is None and rules.items and rules.items[-1].quantity is not None:
                    stated = rules.items[-1].quantity
                if stated is not None:
                    shop_run_qty = stated

                if rules.amount is not None:
                    norm = normalise(turn.body)
                    says_total = any(r.search(norm) for r in _TOTAL_RE)
                    says_rate = any(r.search(norm) for r in _RATE_RE) or any(
                        i.unit_price is not None for i in rules.items
                    )
                    if says_total and not says_rate:
                        shop_total, shop_total_index = rules.amount, index
                        signals.append("shop-stated-total")
                    elif says_rate:
                        quoted_rate = rules.amount
                        signals.append("shop-quoted-rate")
                    elif shop_run_qty is not None and shop_run_qty > 1:
                        quoted_total = rules.amount
                        quoted_total_qty = shop_run_qty
                        signals.append("shop-quoted-total")
                    elif shop_run_qty is not None:
                        quoted_rate = rules.amount
                        signals.append("shop-quoted-rate")
                    elif commit_index >= 0 or len(topics) > 1:
                        # After an order, or across several different
                        # things, one bare number can only be the bill.
                        shop_total, shop_total_index = rules.amount, index
                        signals.append("shop-stated-total")
                    else:
                        quoted_rate = rules.amount
                        signals.append("shop-quoted-rate")
        previous_speaker_was_shop = turn.is_shopkeeper

        if turn.is_customer and (
            is_commitment(turn.body) or is_implicit_order(turn.body, rules)
        ):
            commit_turn, commit_rules, commit_index = turn, rules, index
            if rules.items:
                for named in rules.items:
                    _merge_item(ordered, named)
                last_item_commit_index = index
            if rules.amount is not None and rules.amount_source in (
                "currency-adjacent", "mode-adjacent", "line-totals",
            ) and rules.direction is not Direction.PAYMENT:
                customer_amount = rules.amount

        if (
            turn.is_customer and not rules.items and rules.amount is not None
            and rules.amount_source in ("currency-adjacent", "mode-adjacent")
            and rules.direction is not Direction.PAYMENT
            and _CREDIT_ENTRY.search(normalise(turn.body))
        ):
            credit_entry = (turn, rules.amount)

    out: list[ResolvedOrder] = []
    sale = _resolve_sale(
        thread, category, topics, ordered, topic_turn, commit_turn, commit_rules,
        quoted_rate, quoted_total, quoted_total_qty,
        shop_total if shop_total_index > last_item_commit_index else None,
        customer_amount, signals,
        exclude_mode_from=payment_index,
    )

    if sale is None and credit_entry is not None:
        turn, amount = credit_entry
        sale = ResolvedOrder(
            customer=thread.customer, items=[], amount=amount, mode=PaymentMode.UDHAAR.value,
            direction="sale", canonical_text=turn.body,
            evidence=f"{turn.sender}: {turn.body}", occurred_at=turn.timestamp,
            content_hashes=thread.hashes, signals=["credit-entry"], carried_topic=False,
        )

    paid_separately = payment_turn is not None and payment_rules is not None
    if sale is not None and paid_separately and payment_index > commit_index >= 0:
        # Money sent right after an order, for that order or with no figure
        # of its own, is how the order was PAID -- not a separate debt being
        # cleared. "130 hua total" "paytm kar diya" is one Rs 130 UPI sale;
        # booking it as a sale AND a payment wipes the sale off the khata.
        paid = payment_rules.amount
        if paid is None or sale.amount is None or abs(paid - sale.amount) < 0.01:
            detected, _ = detect_mode(payment_turn.body)
            if detected is not PaymentMode.UNKNOWN:
                sale.mode = detected.value
            elif sale.mode in (PaymentMode.UNKNOWN.value, PaymentMode.UDHAAR.value):
                sale.mode = PaymentMode.CASH.value
            if sale.amount is None and paid is not None:
                sale.amount = paid
            sale.signals.append("paid-on-order")
            paid_separately = False

    if sale is not None:
        out.append(sale)

    if paid_separately:
        # Emitted independently of the sale path: a payment needs no item,
        # no commitment and no topic. The amount is whatever the message
        # stated; when it stated none, None is returned rather than a guess
        # and the review card asks. So is the mode: "paise bhej diye" names
        # no instrument, and calling it cash would be a guess the khata then
        # reports as fact.
        detected, _ = detect_mode(payment_turn.body)
        out.append(ResolvedOrder(
            customer=thread.customer,
            items=[],
            amount=payment_rules.amount,
            mode=detected.value,
            direction="payment",
            canonical_text=payment_turn.body,
            evidence=f"{payment_turn.sender}: {payment_turn.body}",
            occurred_at=payment_turn.timestamp,
            content_hashes=thread.hashes,
            signals=list(dict.fromkeys(signals + ["customer-paid"])),
            carried_topic=False,
        ))
    return out


def _resolve_sale(
    thread: Thread,
    category: Category | str | None,
    topics: dict[tuple[str, str], LineItem],
    ordered: dict[tuple[str, str], LineItem],
    topic_turn: Turn | None,
    commit_turn: Turn | None,
    commit_rules,
    quoted_rate: float | None,
    quoted_total: float | None,
    quoted_total_qty: float | None,
    shop_total: float | None,
    customer_amount: float | None,
    signals: list[str],
    exclude_mode_from: int = -1,
) -> ResolvedOrder | None:
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
    # Everything the customer committed to, across every turn that did --
    # "2 kg chawal bhej do" ... "aur haan 1 packet namak bhi" is one order
    # for two things. Only when no commitment named anything does the topic
    # carry forward, which is the whole reason this module exists.
    if ordered:
        items = [
            LineItem(
                name=i.name, quantity=i.quantity, unit=i.unit,
                modifier=i.modifier, unit_price=i.unit_price,
            )
            for i in ordered.values()
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

    # Anything the commitment named without a quantity, but which the thread
    # established one for earlier, takes it back.
    for item in items:
        if item.quantity is None:
            established = topics.get((item.modifier or "", item.name))
            if established is not None and established.quantity is not None:
                item.quantity = established.quantity
                item.unit = established.unit or item.unit
                if "quantity-from-topic" not in signals:
                    signals.append("quantity-from-topic")

    # ---- the rate -------------------------------------------------------
    # Only as a PER-UNIT price where the quantity is known; multiplying by a
    # quantity we guessed is how a Rs 500 sale becomes Rs 1500. And never on
    # top of a total the shop has already stated for the order.
    if shop_total is None:
        for item in items:
            if item.unit_price is None and quoted_rate is not None and item.quantity is not None:
                item.unit_price = quoted_rate
                if "rate-from-quote" not in signals:
                    signals.append("rate-from-quote")

    # ---- mode -----------------------------------------------------------
    mode = PaymentMode.UNKNOWN.value
    for index, turn in enumerate(thread.turns):
        if index == exclude_mode_from:
            continue
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

    # ---- the amount -----------------------------------------------------
    #
    # A total the shopkeeper stated for THIS order outranks every
    # calculation: it is what was actually charged. Then the arithmetic of a
    # stated rate times a stated quantity; then a quoted bundle total; then
    # what the customer said they would pay. Nothing is ever divided.
    line_totals = [i.line_total for i in items if i.line_total is not None]
    amount = round(sum(line_totals), 2) if len(line_totals) == len(items) and items else None

    if shop_total is not None:
        amount = shop_total
    elif quoted_total is not None and amount is None:
        amount = quoted_total
        if len(items) == 1:
            only = items[0]
            if (
                only.quantity is not None
                and quoted_total_qty is not None
                and abs(only.quantity - quoted_total_qty) > 1e-9
            ):
                signals.append("bundle-quantity-mismatch")
    if amount is None and customer_amount is not None:
        amount = customer_amount
        signals.append("customer-stated-amount")
    if amount is None and all(i.quantity is None for i in items):
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


def resolve(
    thread: Thread,
    category: Category | str | None = None,
) -> ResolvedOrder | None:
    """
    The thread's main order: its sale if it has one, else its payment.

    Kept for callers that want one answer per thread. `resolve_thread`
    returns both when a thread holds a new order and an old debt settled.
    """
    orders = resolve_thread(thread, category)
    return orders[0] if orders else None


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
        for order in resolve_thread(thread, category)
    ]
