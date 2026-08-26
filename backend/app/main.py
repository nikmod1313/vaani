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

from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from .extraction.corrections import CorrectionStore
from .extraction.llm import ClaudeLayer
from .extraction.pipeline import extract, extract_batch
from .extraction.whatsapp import ChatCursor, ingest
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


class Block(BaseModel):
    text: str
    sender_name: str | None = None


class BatchRequest(BaseModel):
    blocks: list[Block]
    known_customers: list[str] = Field(default_factory=list)
    shop_id: str | None = None


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
    )
    return {"results": [r.to_dict() for r in results]}


@app.post("/ingest/export")
def ingest_export(req: IngestRequest) -> dict:
    """
    Ingest a WhatsApp chat export.

    Deliberately does not advance the cursor -- the client does that once the
    user approves the review queue, so a crash mid-review cannot lose orders.
    """
    cursor = ChatCursor(chat_id=req.chat_id)
    if req.cursor:
        cursor.last_timestamp = req.cursor.last_timestamp
        cursor.seen_hashes = set(req.cursor.seen_hashes)

    result = ingest(req.content, cursor, since=req.since, until=req.until)
    extractions = extract_batch(
        [b.text for b in result.candidates],
        known_customers=_customers_for(req.shop_id, req.known_customers),
        senders=[b.sender for b in result.candidates],
        llm=LLM,
    )

    return {
        "total_messages": result.total_messages,
        "new_messages": result.new_messages,
        "blocks": result.blocks,
        "dropped": result.dropped,
        "drop_rate": round(result.drop_rate, 3),
        "candidates": [e.to_dict() for e in extractions],
    }


@app.post("/sync")
def sync(req: SyncRequest) -> dict:
    """
    Bidirectional sync. Idempotent by client-generated transaction id.

    Retrying a batch after a dropped connection updates rows; it never
    invents money.
    """
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
