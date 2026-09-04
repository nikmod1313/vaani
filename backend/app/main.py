"""
VaaniLedger API.

    uvicorn app.main:app --reload

Implements the contract in docs/api.yaml. Extraction, ingestion, sync,
aggregates and the spoken summary are all live against a real SQLite store.

Both external dependencies degrade rather than fail: with no Anthropic key
the pipeline runs rules-only, and with no Sarvam key the summary returns text
for on-device TTS to read. /health says which are live, so nobody reports
degraded numbers as full ones.
"""

from __future__ import annotations

import hashlib
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from .extraction.categories import LABELS, Category
from .extraction.conversation import ResolvedOrder, Turn, build_threads, resolve
from .extraction.numbers import normalise
from .extraction.corrections import CorrectionStore
from .extraction.llm import ClaudeLayer
from .extraction.pipeline import extract, extract_batch
from .extraction.whatsapp import (
    ChatCursor,
    infer_customer,
    ingest,
    ingest_conversation,
    parse_export,
)
from .store import LedgerStore, Transaction
from .summary import SUPPORTED, compose
from .tts import SarvamTTS

DATA = Path("data")

app = FastAPI(
    title="VaaniLedger API",
    version="1.0.0",
    description="Voice- and chat-driven khata for small Indian shopkeepers.",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000", "http://localhost:5173"],
    allow_methods=["*"],
    allow_headers=["*"],
)

STORE = LedgerStore(DATA / "ledger.db")
CORRECTIONS = CorrectionStore(DATA / "corrections.jsonl")

_layer = ClaudeLayer(corrections=CORRECTIONS)
LLM = _layer if _layer.available() else None

TTS = SarvamTTS(cache_dir=DATA / "audio_cache")


# --------------------------------------------------------------------------
# Models
# --------------------------------------------------------------------------

class ExtractRequest(BaseModel):
    text: str
    sender_name: str | None = None
    known_customers: list[str] = Field(default_factory=list)
    shop_id: str | None = None
    source: str = "chat"
    #: Overrides the shop's stored category for this call. Normally omitted --
    #: the shop's own setting is the right answer and the client need not
    #: repeat it on every request.
    category: str | None = None


class Block(BaseModel):
    text: str
    sender_name: str | None = None


class BatchRequest(BaseModel):
    blocks: list[Block]
    known_customers: list[str] = Field(default_factory=list)
    shop_id: str | None = None
    category: str | None = None


class CursorModel(BaseModel):
    chat_id: str
    last_timestamp: datetime | None = None
    seen_hashes: list[str] = Field(default_factory=list)


class IngestRequest(BaseModel):
    chat_id: str
    content: str
    since: datetime | None = None
    until: datetime | None = None
    cursor: CursorModel | None = None
    known_customers: list[str] = Field(default_factory=list)
    shop_id: str | None = None
    category: str | None = None
    #: The other party in the chat. Supplying it switches on the conversation
    #: lane, which is the only one that can resolve an order split across
    #: turns ("blue kurti?" ... "mujhe 3 chahiye").
    customer_name: str | None = None
    #: Names the export uses for the shop's own side, beyond "you"/"me".
    shop_aliases: list[str] = Field(default_factory=list)
    lane: str | None = None


class ScreenMessage(BaseModel):
    """One message read off the screen by the accessibility capture."""

    body: str
    sender: str | None = None
    timestamp: datetime | None = None
    #: The phone owner sent it -- i.e. the shopkeeper, not the customer.
    is_outgoing: bool = False
    content_hash: str | None = None


class ScreenRequest(BaseModel):
    chat_id: str
    customer_name: str
    messages: list[ScreenMessage] = Field(default_factory=list)
    cursor: CursorModel | None = None
    shop_id: str | None = None
    category: str | None = None
    known_customers: list[str] = Field(default_factory=list)


class TransactionModel(BaseModel):
    id: str
    amount: float
    mode: str
    direction: str = "sale"
    customer_name: str | None = None
    items: list[str] = Field(default_factory=list)
    source: str = "manual"
    confidence: float = 0.0
    occurred_at: str = ""
    deleted: bool = False


class SyncRequest(BaseModel):
    shop_id: str
    shop_name: str | None = None
    shop_category: str | None = None
    since: datetime | None = None
    transactions: list[TransactionModel] = Field(default_factory=list)


class CorrectionRequest(BaseModel):
    shop_id: str = "default"
    raw_text: str
    predicted: dict = Field(default_factory=dict)
    corrected: dict


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

def _customers_for(shop_id: str | None, supplied: list[str]) -> list[str]:
    """
    The shop's real customer list beats whatever the client sent.

    The phone's copy can be stale; the server's is authoritative, and a stale
    list is how you get a second "Rahul Verma".
    """
    if shop_id:
        known = STORE.customer_names(shop_id)
        if known:
            return known
    return supplied


def _category_for(shop_id: str | None, supplied: str | None) -> str:
    """
    Which item lexicon to use.

    An explicit override wins, then the shop's stored setting, then the open
    vocabulary. The shop's own choice is looked up rather than trusted from
    the request, for the same reason the customer list is: the phone's copy
    can be stale, and a stale category silently stops recognising half of
    what the shop sells.
    """
    if supplied:
        return Category.parse(supplied).value
    if shop_id:
        shop = STORE.get_shop(shop_id)
        if shop and shop.get("category"):
            return Category.parse(shop["category"]).value
    return Category.GENERAL.value


def _order_to_dict(order: ResolvedOrder, category: str) -> dict:
    """
    A resolved order, in the shape the review queue renders.

    The extraction engine is re-run over the CANONICAL text so confidence
    scoring and name resolution stay in exactly one place, but `text` sent
    back to the client is the EVIDENCE -- the messages the customer actually
    sent. Showing someone a sentence they never wrote, as proof of what they
    owe, loses the argument the khata exists to settle.
    """
    result = extract(
        order.canonical_text,
        known_customers=[order.customer] if order.customer else [],
        sender_name=order.customer,
        llm=LLM,
        category=category,
    )
    payload = result.to_dict()
    payload["text"] = order.evidence
    payload["canonical_text"] = order.canonical_text
    # The resolver already did the arithmetic across turns; a single-message
    # re-parse of the canonical form cannot see a rate quoted three messages
    # earlier, so its amount must not overwrite one that it can.
    if order.amount is not None:
        payload["amount"] = order.amount
    if order.mode and order.mode != "unknown":
        payload["mode"] = order.mode
    payload["line_items"] = [i.to_dict() for i in order.items]
    payload["occurred_at"] = order.occurred_at.isoformat() if order.occurred_at else None
    payload["content_hashes"] = order.content_hashes
    payload["signals"] = order.signals
    payload["carried_topic"] = order.carried_topic
    payload["source"] = "conversation"
    return payload


# --------------------------------------------------------------------------
# Endpoints
# --------------------------------------------------------------------------

@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "version": "1.0.0",
        "stage2": _layer.model if LLM else "unavailable (rules-only)",
        "tts": "sarvam bulbul:v3" if TTS.available() else "unavailable (text only)",
        "corrections": len(CORRECTIONS),
    }


@app.post("/extract")
def extract_one(req: ExtractRequest) -> dict:
    """Single message -> transaction candidate with per-field confidence."""
    result = extract(
        req.text,
        known_customers=_customers_for(req.shop_id, req.known_customers),
        sender_name=req.sender_name,
        llm=LLM,
        category=_category_for(req.shop_id, req.category),
    )
    return result.to_dict()


@app.post("/extract/batch")
def extract_many(req: BatchRequest) -> dict:
    """Bulk path -- many blocks, one model request."""
    results = extract_batch(
        [b.text for b in req.blocks],
        known_customers=_customers_for(req.shop_id, req.known_customers),
        senders=[b.sender_name for b in req.blocks],
        llm=LLM,
        category=_category_for(req.shop_id, req.category),
    )
    return {"results": [r.to_dict() for r in results]}


@app.post("/ingest/export")
def ingest_export(req: IngestRequest) -> dict:
    """
    Ingest a WhatsApp chat export.

    TWO LANES, and exactly one runs per request.

    `conversation` threads both sides of the chat together and resolves an
    order stated across turns -- the customer asks what is available, the
    shop quotes, the customer commits with only a quantity. It needs to know
    which participant is the customer, so it runs when `customer_name` is
    supplied, which is the default for a real chat.

    `blocks` is the original per-sender lane. It runs when nobody named a
    customer, and for a shopkeeper dictating entries to himself it is still
    the better unit.

    Running both would offer the same order twice in one review queue, and
    approving both would book it twice. So `lane` picks, and the response
    says which one answered.

    Deliberately does not advance the cursor -- the client does that once the
    user approves the review queue, so a crash mid-review cannot lose orders.
    """
    cursor = ChatCursor(chat_id=req.chat_id)
    if req.cursor:
        cursor.last_timestamp = req.cursor.last_timestamp
        cursor.seen_hashes = set(req.cursor.seen_hashes)

    category = _category_for(req.shop_id, req.category)
    lane = req.lane or ("conversation" if req.customer_name else "blocks")

    if lane == "conversation":
        aliases = set(req.shop_aliases)

        # Two very different failures used to share one unhelpful 400, and
        # the phone rendered both as "server not found" -- which sent whoever
        # hit it looking at the network instead of at the file they picked.
        # They are told apart here so the app can say which one happened.
        parsed = parse_export(req.content)
        if not parsed:
            raise HTTPException(
                400,
                {
                    "error": "not_a_whatsapp_export",
                    "message": (
                        "No messages found. This file does not look like a "
                        "WhatsApp chat export -- export the chat from "
                        "WhatsApp with 'Without media' and try that file."
                    ),
                    "bytes": len(req.content),
                    "first_line": req.content.strip().splitlines()[0][:120]
                    if req.content.strip()
                    else "",
                },
            )

        # The phone knows its own shop name but rarely the contact name
        # WhatsApp wrote into the export, so let the server work out who the
        # other party is rather than making the client guess or prompt.
        customer = req.customer_name or infer_customer(parsed, aliases)
        if not customer:
            senders = sorted({m.sender for m in parsed})
            raise HTTPException(
                400,
                {
                    "error": "customer_unknown",
                    "message": (
                        "Could not tell which participant is the customer. "
                        "Every sender matched the shop's own names."
                    ),
                    "participants": senders,
                    "shop_aliases": sorted(aliases),
                },
            )
        convo = ingest_conversation(
            req.content,
            cursor,
            customer=customer,
            category=category,
            since=req.since,
            until=req.until,
            shop_aliases=aliases or None,
        )
        return {
            "lane": "conversation",
            "category": category,
            "customer": customer,
            "total_messages": convo.total_messages,
            "new_messages": convo.new_messages,
            "threads": convo.threads,
            "orders": [_order_to_dict(o, category) for o in convo.orders],
            "candidates": [],
        }

    result = ingest(req.content, cursor, since=req.since, until=req.until)
    extractions = extract_batch(
        [b.text for b in result.candidates],
        known_customers=_customers_for(req.shop_id, req.known_customers),
        senders=[b.sender for b in result.candidates],
        llm=LLM,
        category=category,
    )

    return {
        "lane": "blocks",
        "category": category,
        "total_messages": result.total_messages,
        "new_messages": result.new_messages,
        "blocks": result.blocks,
        "dropped": result.dropped,
        "drop_rate": round(result.drop_rate, 3),
        "candidates": [e.to_dict() for e in extractions],
        "orders": [],
    }


@app.post("/ingest/screen")
def ingest_screen(req: ScreenRequest) -> dict:
    """
    Ingest messages read off the screen by the accessibility capture.

    The live lane. Unlike an export this arrives already structured -- the
    capture knows which side sent each bubble -- so there is nothing to
    parse, only to thread and resolve.

    The same watermark guards it. A message seen on screen, then again in a
    later export of the same chat, hashes identically and is booked once.
    That is the whole reason the two lanes share a cursor rather than
    keeping one each.
    """
    cursor = ChatCursor(chat_id=req.chat_id)
    if req.cursor:
        cursor.last_timestamp = req.cursor.last_timestamp
        cursor.seen_hashes = set(req.cursor.seen_hashes)

    category = _category_for(req.shop_id, req.category)

    turns: list[Turn] = []
    seen_this_batch: set[str] = set()
    for m in req.messages:
        digest = m.content_hash or hashlib.sha1(
            f"{(m.sender or '').strip().lower()}|"
            f"{m.timestamp.isoformat() if m.timestamp else ''}|"
            f"{normalise(m.body)}".encode("utf-8")
        ).hexdigest()

        # A scroll-harvest re-reads rows as the list moves; the same bubble
        # arriving twice in one batch is normal, not an error.
        if digest in seen_this_batch or digest in cursor.seen_hashes:
            continue
        seen_this_batch.add(digest)

        turns.append(
            Turn(
                sender=m.sender or (req.customer_name if not m.is_outgoing else "You"),
                body=m.body,
                timestamp=m.timestamp,
                is_shopkeeper=m.is_outgoing,
                content_hash=digest,
            )
        )

    threads = build_threads(turns, req.customer_name)
    orders = [o for t in threads if (o := resolve(t, category)) is not None]

    return {
        "lane": "screen",
        "category": category,
        "total_messages": len(req.messages),
        "new_messages": len(turns),
        "threads": len(threads),
        "orders": [_order_to_dict(o, category) for o in orders],
    }


@app.get("/categories")
def categories() -> dict:
    """
    The shop categories the picker offers.

    Served rather than hardcoded in the app so adding a category ships
    without an APK update -- the phone renders whatever this returns.
    """
    return {
        "categories": [
            {"id": c.value, "label": LABELS[c]}
            for c in Category
        ],
        "default": Category.GENERAL.value,
    }


@app.post("/sync")
def sync(req: SyncRequest) -> dict:
    """
    Bidirectional sync. Idempotent by client-generated transaction id.

    Retrying a batch after a dropped connection updates rows; it never
    invents money.
    """
    # Name the shop properly when the client knows it; _ensure_shop would
    # otherwise leave the id standing in as the name.
    if req.shop_name or req.shop_category:
        STORE.create_shop(
            req.shop_id,
            req.shop_name or req.shop_id,
            category=Category.parse(req.shop_category).value if req.shop_category else None,
        )

    incoming = [
        Transaction(
            id=t.id,
            shop_id=req.shop_id,
            amount=t.amount,
            mode=t.mode,
            direction=t.direction,
            customer_name=t.customer_name,
            items=t.items,
            source=t.source,
            confidence=t.confidence,
            occurred_at=t.occurred_at,
            deleted=t.deleted,
        )
        for t in req.transactions
    ]
    accepted, duplicates = STORE.save_many(incoming)

    since = req.since.isoformat(timespec="seconds") if req.since else None
    return {
        "accepted": accepted,
        "duplicates": duplicates,
        "transactions": STORE.changed_since(req.shop_id, since),
    }


@app.get("/shops/{shop_id}/summary")
def shop_summary(shop_id: str) -> dict:
    """KPIs, the 14-day series, and per-customer balances."""
    if not STORE.get_shop(shop_id):
        raise HTTPException(404, f"unknown shop {shop_id!r}")
    return STORE.summary(shop_id)


@app.get("/summary/narrate")
def narrate(
    shop_id: str,
    lang: str = "hi-IN",
    speaker: str = "anushka",
) -> dict:
    """
    The spoken daily summary.

    Text is composed from a TEMPLATE filled with real aggregates -- no model
    ever restates the figures. Bulbul only reads what the store computed.

    With no Sarvam key, returns the text anyway so the app can fall back to
    Android's on-device TextToSpeech.
    """
    if not STORE.get_shop(shop_id):
        raise HTTPException(404, f"unknown shop {shop_id!r}")
    if lang not in SUPPORTED:
        raise HTTPException(400, f"unsupported language {lang!r}")

    narration = compose(STORE.summary(shop_id), lang)
    payload = {
        "text": narration.text,
        "language_code": narration.language_code,
        "characters": len(narration),
        "audio_url": None,
        "cache_hit": False,
        "fallback": None,
    }

    if not TTS.available():
        payload["fallback"] = "on-device"
        return payload

    try:
        speech = TTS.synthesize(narration.text, narration.language_code, speaker)
        payload["audio_url"] = f"/audio/{speech.path.name}"
        payload["cache_hit"] = speech.cache_hit
    except Exception as exc:
        # Never fail the whole request over audio -- the text is the useful
        # part and the phone can speak it locally.
        payload["fallback"] = "on-device"
        payload["error"] = f"{type(exc).__name__}: {exc}"

    return payload


@app.get("/audio/{filename}")
def audio(filename: str) -> FileResponse:
    """Serve cached speech. Filenames are content hashes, so they are stable."""
    if "/" in filename or ".." in filename:
        raise HTTPException(400, "bad filename")
    path = TTS.cache_dir / filename
    if not path.exists():
        raise HTTPException(404, "not found")
    return FileResponse(path, media_type="audio/mpeg")


@app.post("/corrections")
def add_correction(req: CorrectionRequest) -> dict:
    """
    Record a Badlo.

    Stored as a (raw_text -> corrected_json) pair and retrieved later as a
    per-shop few-shot example. This is the learning loop.
    """
    CORRECTIONS.add(
        raw_text=req.raw_text,
        corrected=req.corrected,
        predicted=req.predicted,
        shop_id=req.shop_id,
    )
    return {
        "stored": len(CORRECTIONS),
        "weakest_fields": CORRECTIONS.field_error_counts(req.shop_id),
    }


@app.post("/shops/{shop_id}/seed")
def seed(shop_id: str, days: int = 90) -> dict:
    """Load demo data. Never present on an empty database."""
    return STORE.seed(shop_id, days=days)


# --------------------------------------------------------------------------
# Dashboard
# --------------------------------------------------------------------------
#
# Served from the API's own origin so there is no CORS hop and no build step
# between the team and a working page. Mounted last so it cannot shadow an
# API route.

DASHBOARD = Path(__file__).resolve().parents[2] / "dashboard"


@app.get("/")
def dashboard() -> FileResponse:
    index = DASHBOARD / "index.html"
    if not index.exists():
        raise HTTPException(404, "dashboard not built")
    return FileResponse(index, media_type="text/html")
