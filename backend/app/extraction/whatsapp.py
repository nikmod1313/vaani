"""
WhatsApp chat ingestion.

Parses the .txt produced by WhatsApp's built-in "Export chat", then runs the
bulk pipeline over it:

    parse -> watermark -> segment -> classify -> (extract) -> review queue

The watermark is the important part. Deciding whether a message has already
been processed is NOT a language problem -- it is a content hash compared
against a cursor. A model that is 99% right about "have I seen this before"
still puts duplicate money in someone's khata one time in a hundred.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field
from datetime import datetime

from .conversation import ResolvedOrder, Turn, build_threads, resolve_thread
from .numbers import normalise
from .rules import _ITEMS, _MODE_WORDS, _UNITS, apply_rules

# --------------------------------------------------------------------------
# Export formats
# --------------------------------------------------------------------------

# [26/08/26, 9:41:23 AM] Rahul Verma: message
_BRACKETED = re.compile(
    r"^\[(?P<date>\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}),?\s+"
    r"(?P<time>\d{1,2}:\d{2}(?::\d{2})?\s*(?:[APap]\.?[Mm]\.?)?)\]\s*"
    r"(?P<sender>[^:]{1,60}?):(?:\s(?P<body>.*))?$"
)

# 26/08/26, 9:41 am - Rahul Verma: message
_DASHED = re.compile(
    r"^(?P<date>\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}),?\s+"
    # The separator is a hyphen on most builds and an en-dash on some
    # locales. Matching only the hyphen silently parses zero messages out of
    # an otherwise perfect export.
    r"(?P<time>\d{1,2}:\d{2}(?::\d{2})?\s*(?:[APap]\.?[Mm]\.?)?)\s+[-\u2013\u2014]\s+"
    r"(?P<sender>[^:]{1,60}?):(?:\s(?P<body>.*))?$"
)

# A timestamped line with no "Sender:" is WhatsApp narrating -- "Isha changed
# the group name from ...", "Rahul left". Not matching it as a header made it
# a CONTINUATION of the message above, so its date and time were read as
# numbers: an order booked as Rs 19 from "19/09/2026, 19:08".
_HEADER_ONLY = re.compile(
    r"^\[?\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4},?\s+"
    r"\d{1,2}:\d{2}(?::\d{2})?\s*(?:[APap]\.?[Mm]\.?)?\]?\s*[-\u2013\u2014]?\s*"
)

# WhatsApp's own annotation on an edited message, not the customer's words.
_EDITED = re.compile(r"\s*\u200e?<\s*this message was edited\s*>\s*$", re.IGNORECASE)

# System lines carry no sender and must never become ledger entries.
_SYSTEM_MARKERS = (
    "messages and calls are end-to-end encrypted",
    "you deleted this message",
    "this message was deleted",
    "<media omitted>",
    "media omitted",
    "missed voice call",
    "missed video call",
    "changed the subject",
    "changed this group's icon",
    "created group",
    "added you",
    "joined using this group's invite link",
    "security code changed",
    "changed the group name",
    "changed the group description",
    "created this group",
    "image omitted", "video omitted", "audio omitted", "sticker omitted",
    "gif omitted", "document omitted", "contact card omitted",
)
# "left" used to be a marker too, and dropped every real message saying
# "only 2 left". A member leaving is a sender-less line, caught by
# _HEADER_ONLY instead.

_DATE_FORMATS = (
    "%d/%m/%y", "%d/%m/%Y", "%m/%d/%y", "%m/%d/%Y",
    "%d-%m-%y", "%d-%m-%Y", "%d.%m.%y", "%d.%m.%Y",
)
_TIME_FORMATS = ("%I:%M:%S %p", "%I:%M %p", "%H:%M:%S", "%H:%M")


@dataclass
class Message:
    timestamp: datetime | None
    sender: str
    body: str
    raw: str

    @property
    def content_hash(self) -> str:
        """
        Stable identity for a message.

        Sender + timestamp + normalised body. Normalising the body means an
        invisible whitespace change between two exports does not produce a
        second hash for the same message.
        """
        ts = self.timestamp.isoformat() if self.timestamp else ""
        payload = f"{self.sender.strip().lower()}|{ts}|{normalise(self.body)}"
        return hashlib.sha1(payload.encode("utf-8")).hexdigest()


@dataclass
class Block:
    """Consecutive messages from one sender, treated as a single utterance."""

    sender: str
    messages: list[Message]

    @property
    def text(self) -> str:
        return " ".join(m.body for m in self.messages)

    @property
    def timestamp(self) -> datetime | None:
        return self.messages[0].timestamp if self.messages else None

    @property
    def hashes(self) -> list[str]:
        return [m.content_hash for m in self.messages]


@dataclass
class ChatCursor:
    """
    Per-chat high-water mark.

    Holds the last processed timestamp plus a rolling window of recent hashes.
    The timestamp alone is not enough -- exports overlap, clocks skew, and the
    same minute can hold several messages.
    """

    chat_id: str
    last_timestamp: datetime | None = None
    seen_hashes: set[str] = field(default_factory=set)
    max_hashes: int = 500

    def has_seen(self, message: Message) -> bool:
        if message.content_hash in self.seen_hashes:
            return True
        if (
            self.last_timestamp
            and message.timestamp
            and message.timestamp < self.last_timestamp
        ):
            return True
        return False

    def advance(self, messages: list[Message]) -> None:
        for m in messages:
            self.seen_hashes.add(m.content_hash)
            if m.timestamp and (
                self.last_timestamp is None or m.timestamp > self.last_timestamp
            ):
                self.last_timestamp = m.timestamp
        if len(self.seen_hashes) > self.max_hashes:
            # Keep the window bounded; the timestamp covers older history.
            self.seen_hashes = set(list(self.seen_hashes)[-self.max_hashes:])


# --------------------------------------------------------------------------
# Parsing
# --------------------------------------------------------------------------

def _parse_timestamp(date_str: str, time_str: str) -> datetime | None:
    time_str = re.sub(r"\s+", " ", time_str.strip().replace(".", "")).upper()
    for date_fmt in _DATE_FORMATS:
        for time_fmt in _TIME_FORMATS:
            try:
                return datetime.strptime(f"{date_str} {time_str}", f"{date_fmt} {time_fmt}")
            except ValueError:
                continue
    return None


def _is_system_line(body: str) -> bool:
    low = body.strip().lower()
    return not low or low == "null" or any(marker in low for marker in _SYSTEM_MARKERS)


def parse_export(content: str) -> list[Message]:
    """
    Parse a WhatsApp export into messages.

    Continuation lines (a message containing newlines) carry no timestamp
    prefix and are appended to the message above them.
    """
    messages: list[Message] = []

    for line in content.splitlines():
        # WhatsApp prefixes exports with a U+200E left-to-right mark.
        stripped = line.lstrip("‎‏").rstrip()
        if not stripped:
            continue

        match = _BRACKETED.match(stripped) or _DASHED.match(stripped)
        if match:
            raw_body = match.group("body") or ""
            # iPhone exports mark WhatsApp's own notes -- media placeholders,
            # "X changed the group name" -- with a leading U+200E, and put a
            # sender's name on them. Words a person typed never start with it.
            if raw_body.startswith("\u200e"):
                continue
            body = _EDITED.sub("", raw_body).strip()
            if _is_system_line(body):
                continue
            messages.append(
                Message(
                    timestamp=_parse_timestamp(match.group("date"), match.group("time")),
                    sender=match.group("sender").strip(),
                    body=body,
                    raw=stripped,
                )
            )
        elif _HEADER_ONLY.match(stripped):
            continue
        elif messages:
            # Continuation of the previous message.
            stripped = _EDITED.sub("", stripped)
            if not stripped:
                continue
            messages[-1].body += "\n" + stripped
            messages[-1].raw += "\n" + stripped

    return messages


# --------------------------------------------------------------------------
# Segmentation
# --------------------------------------------------------------------------

def segment(messages: list[Message], window_seconds: int = 180) -> list[Block]:
    """
    Group consecutive messages from the same sender inside a time window.

    Orders routinely arrive across three messages -- "2 kilo chawal" /
    "aur 1 chai" / "udhaar likh lena" -- and mean nothing apart.
    """
    blocks: list[Block] = []
    for message in messages:
        if blocks and blocks[-1].sender == message.sender:
            previous = blocks[-1].messages[-1]
            if (
                previous.timestamp
                and message.timestamp
                and (message.timestamp - previous.timestamp).total_seconds() <= window_seconds
            ):
                blocks[-1].messages.append(message)
                continue
        blocks.append(Block(sender=message.sender, messages=[message]))
    return blocks


# --------------------------------------------------------------------------
# Classification
# --------------------------------------------------------------------------

@dataclass
class Classification:
    is_order: bool
    score: float
    signals: list[str]


def classify(text: str) -> Classification:
    """
    Cheap gate: could this block be a transaction?

    Rules only, deliberately. Most chat traffic is "ok", "thik hai",
    "namaste" -- dropping it here keeps roughly 70% of blocks away from the
    model, which is the difference between one API call and twenty.

    Tuned for recall: a false positive costs one card in the review queue, a
    false negative loses a real order.
    """
    norm = normalise(text)
    tokens = set(norm.split())
    signals: list[str] = []
    score = 0.0

    if any(t in _MODE_WORDS for t in tokens):
        signals.append("payment-word")
        score += 0.4
    if any(t in _UNITS for t in tokens):
        signals.append("unit")
        score += 0.3
    if any(t in _ITEMS for t in tokens):
        signals.append("item")
        score += 0.3
    if re.search(r"(?:₹|rs\.?|rupaye|रु)", norm) or re.search(r"\b\d{2,}\b", norm):
        signals.append("amount")
        score += 0.35

    rules = apply_rules(text)
    if rules.all_numbers:
        signals.append("number")
        score += 0.15

    return Classification(is_order=score >= 0.3, score=min(score, 1.0), signals=signals)


# --------------------------------------------------------------------------
# The bulk pipeline
# --------------------------------------------------------------------------

@dataclass
class IngestResult:
    total_messages: int
    new_messages: int
    blocks: int
    candidates: list[Block]
    dropped: int

    @property
    def drop_rate(self) -> float:
        return self.dropped / self.blocks if self.blocks else 0.0


def ingest(
    content: str,
    cursor: ChatCursor,
    since: datetime | None = None,
    until: datetime | None = None,
) -> IngestResult:
    """
    Run an export through the full bulk pipeline.

    Returns the blocks worth extracting. The caller advances the cursor only
    after the user has approved the review queue -- never here, or a crash
    mid-review would silently lose orders.
    """
    messages = parse_export(content)
    total = len(messages)

    fresh = [m for m in messages if not cursor.has_seen(m)]
    if since:
        fresh = [m for m in fresh if m.timestamp and m.timestamp >= since]
    if until:
        fresh = [m for m in fresh if m.timestamp and m.timestamp <= until]

    blocks = segment(fresh)
    candidates = [b for b in blocks if classify(b.text).is_order]

    return IngestResult(
        total_messages=total,
        new_messages=len(fresh),
        blocks=len(blocks),
        candidates=candidates,
        dropped=len(blocks) - len(candidates),
    )


# --------------------------------------------------------------------------
# Conversation lane
# --------------------------------------------------------------------------
#
# `ingest` above answers "which blocks might be orders". That is the right
# question for a shopkeeper dictating entries to himself, and the wrong one
# for a two-sided negotiation, where the item and the quantity are spoken by
# different people several turns apart. Everything below threads the two
# sides instead. Both lanes run over the same watermark, so an order can
# never be booked twice by arriving down both.

# Names the export uses for the phone's own owner.
_SELF_ALIASES = {"you", "me", "aap", "आप"}


def is_shopkeeper(sender: str, customer: str, aliases: set[str] | None = None) -> bool:
    """
    Which side of the chat sent this?

    Decided by naming the CUSTOMER rather than the shop. A 1:1 chat has
    exactly two participants and the customer's name is the one thing the
    app reliably knows -- it is the chat title, and on the phone it is the
    contact the bubble was opened over. Trying to recognise the shopkeeper by
    name instead fails on the first export, where WhatsApp writes the owner's
    own contact name and nobody has told us what that is.
    """
    who = sender.strip().lower()
    if who in _SELF_ALIASES:
        return True
    if aliases and who in {a.strip().lower() for a in aliases}:
        return True
    return who != customer.strip().lower()


def infer_customer(
    messages: list[Message],
    shop_aliases: set[str] | None = None,
) -> str | None:
    """
    Work out which participant is the customer.

    A WhatsApp export written on the shopkeeper's phone names the owner by
    their OWN contact name, not "You", so there is no fixed string to look
    for. What the app does know is its own shop name -- so eliminate that,
    eliminate the self-aliases, and in a 1:1 chat exactly one participant is
    left.

    Picks the most talkative remaining sender when several are left, which
    is the right answer for a group and a harmless one for a 1:1. Returns
    None when nobody qualifies, and the caller then has to ask rather than
    guess -- attributing an order to the wrong person is worse than pausing
    to check.
    """
    excluded = {a.strip().lower() for a in (shop_aliases or set())} | _SELF_ALIASES

    counts: dict[str, int] = {}
    for m in messages:
        who = m.sender.strip()
        if who.lower() in excluded:
            continue
        counts[who] = counts.get(who, 0) + 1

    if not counts:
        return None
    return max(counts, key=lambda name: counts[name])


def to_turns(
    messages: list[Message],
    customer: str,
    shop_aliases: set[str] | None = None,
) -> list[Turn]:
    """Parsed export messages -> conversation turns."""
    return [
        Turn(
            sender=m.sender,
            body=m.body,
            timestamp=m.timestamp,
            is_shopkeeper=is_shopkeeper(m.sender, customer, shop_aliases),
            content_hash=m.content_hash,
        )
        for m in messages
        # The shop's own Vaani reminder, read back from the export: its
        # balance was taken as the next order's total.
        if not (is_shopkeeper(m.sender, customer, shop_aliases) and _is_reminder(m.body))
    ]


def _is_reminder(text: str) -> bool:
    """Mirror of ReminderLinks.isReminder on the phone."""
    t = text.lower()
    return ("namaste" in t or "नमस्ते" in t) and (
        "baaki hai" in t or "बाकी हैं" in t or "is pending as of" in t
    )


@dataclass
class ConversationResult:
    total_messages: int
    new_messages: int
    threads: int
    orders: list[ResolvedOrder]

    @property
    def hashes(self) -> list[str]:
        """Every hash the caller should record once the user has approved."""
        return [h for order in self.orders for h in order.content_hashes]


def ingest_conversation(
    content: str,
    cursor: ChatCursor,
    customer: str,
    category: str | None = None,
    since: datetime | None = None,
    until: datetime | None = None,
    shop_aliases: set[str] | None = None,
    gap_minutes: int = 45,
) -> ConversationResult:
    """
    Run an export through the conversation lane.

    Like `ingest`, this deliberately does NOT advance the cursor. The caller
    advances it after the shopkeeper approves the review queue, so a crash
    mid-review re-offers the orders instead of losing them.
    """
    messages = parse_export(content)
    total = len(messages)

    fresh = [m for m in messages if not cursor.has_seen(m)]
    if since:
        fresh = [m for m in fresh if m.timestamp and m.timestamp >= since]
    if until:
        fresh = [m for m in fresh if m.timestamp and m.timestamp <= until]

    turns = to_turns(fresh, customer, shop_aliases)
    threads = build_threads(turns, customer, gap_minutes)
    orders = [o for t in threads for o in resolve_thread(t, category)]

    return ConversationResult(
        total_messages=total,
        new_messages=len(fresh),
        threads=len(threads),
        orders=orders,
    )
