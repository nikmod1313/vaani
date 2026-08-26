"""
VaaniLedger API.

    uvicorn app.main:app --reload

Implements the contract in docs/api.yaml. The extraction endpoints are real;
sync and summary are stubs returning contract-shaped data so the Android app
and dashboard can be built against them from day one.
"""

from __future__ import annotations

from datetime import datetime

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .extraction.pipeline import extract, extract_batch
from .extraction.whatsapp import ChatCursor, ingest

app = FastAPI(
    title="VaaniLedger API",
    version="1.0.0",
    description="Voice- and chat-driven khata for small Indian shopkeepers.",
)

# The dashboard is served from a different origin in dev.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000", "http://localhost:5173"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# --------------------------------------------------------------------------
# Request models
# --------------------------------------------------------------------------

class ExtractRequest(BaseModel):
    text: str
    sender_name: str | None = None
    known_customers: list[str] = Field(default_factory=list)
    source: str = "chat"


class Block(BaseModel):
    text: str
    sender_name: str | None = None


class BatchRequest(BaseModel):
    blocks: list[Block]
    known_customers: list[str] = Field(default_factory=list)


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


# --------------------------------------------------------------------------
# Endpoints
# --------------------------------------------------------------------------

@app.get("/health")
def health() -> dict:
    return {"status": "ok", "version": "1.0.0"}


@app.post("/extract")
def extract_one(req: ExtractRequest) -> dict:
    """Single message -> transaction candidate with per-field confidence."""
    result = extract(
        req.text,
        known_customers=req.known_customers,
        sender_name=req.sender_name,
    )
    return result.to_dict()


@app.post("/extract/batch")
def extract_many(req: BatchRequest) -> dict:
    """Bulk path -- many blocks, one request."""
    results = extract_batch(
        [b.text for b in req.blocks],
        known_customers=req.known_customers,
        senders=[b.sender_name for b in req.blocks],
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
        known_customers=req.known_customers,
        senders=[b.sender for b in result.candidates],
    )

    return {
        "total_messages": result.total_messages,
        "new_messages": result.new_messages,
        "blocks": result.blocks,
        "dropped": result.dropped,
        "drop_rate": round(result.drop_rate, 3),
        "candidates": [e.to_dict() for e in extractions],
    }
