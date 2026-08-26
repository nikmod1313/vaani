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

from .numbers import normalise
from .rules import _ITEMS, _MODE_WORDS, _UNITS, apply_rules

# --------------------------------------------------------------------------
# Export formats
# --------------------------------------------------------------------------

# [26/08/26, 9:41:23 AM] Rahul Verma: message
_BRACKETED = re.compile(
    r"^\[(?P<date>\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}),?\s+"
    r"(?P<time>\d{1,2}:\d{2}(?::\d{2})?\s*(?:[APap]\.?[Mm]\.?)?)\]\s*"
    r"(?P<sender>[^:]{1,60}?):\s(?P<body>.*)$"
)

# 26/08/26, 9:41 am - Rahul Verma: message
_DASHED = re.compile(
    r"^(?P<date>\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}),?\s+"
    r"(?P<time>\d{1,2}:\d{2}(?::\d{2})?\s*(?:[APap]\.?[Mm]\.?)?)\s+-\s+"
    r"(?P<sender>[^:]{1,60}?):\s(?P<body>.*)$"
)

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
    "left",
    "joined using this group's invite link",
    "security code changed",
    "null",
)

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
    return any(marker in low for marker in _SYSTEM_MARKERS)


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
            body = match.group("body").strip()
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
        elif messages:
            # Continuation of the previous message.
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
