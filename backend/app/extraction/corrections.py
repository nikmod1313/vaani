"""
The correction store.

Every time a shopkeeper taps "Badlo" and fixes a field, that becomes a
(raw_text -> corrected_json) pair. Similar pairs are retrieved later and fed
to the model as few-shot examples for that shop specifically.

This is what "the system learns from your corrections" actually means. It is
per-shop on purpose: one shop's "chai" is 1 packet for 10 rupees, another's is
a 250g box for 90, and a shared model cannot be right for both.

Similarity uses the same token_set_ratio as customer matching rather than
embeddings -- no extra dependency, no vector store, and on short shop messages
lexical overlap is a strong signal. Swap in embeddings later behind the same
`similar()` interface if the eval says it helps.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from .names import token_set_ratio
from .numbers import normalise


@dataclass
class Correction:
    raw_text: str
    corrected: dict
    shop_id: str = "default"
    predicted: dict = field(default_factory=dict)
    created_at: str = ""

    def __post_init__(self) -> None:
        if not self.created_at:
            self.created_at = datetime.now().isoformat(timespec="seconds")

    def as_json(self) -> str:
        """The shape the model should imitate -- corrected fields only."""
        keep = ("amount", "mode", "direction", "items", "customer")
        return json.dumps(
            {k: v for k, v in self.corrected.items() if k in keep},
            ensure_ascii=False,
        )

    def changed_fields(self) -> list[str]:
        """Which fields the human actually had to fix."""
        return [
            k for k, v in self.corrected.items()
            if k in self.predicted and self.predicted[k] != v
        ]

    def to_dict(self) -> dict:
        return {
            "raw_text": self.raw_text,
            "corrected": self.corrected,
            "predicted": self.predicted,
            "shop_id": self.shop_id,
            "created_at": self.created_at,
        }


class CorrectionStore:
    """
    JSONL-backed store, one row per correction.

    Deliberately a flat file: it is inspectable during the demo, diffable in
    git, and trivially swappable for the Postgres table once sync lands. The
    interface is what matters, not the backing.
    """

    def __init__(self, path: Path | str | None = None) -> None:
        self.path = Path(path) if path else None
        self._rows: list[Correction] = []
        if self.path and self.path.exists():
            self._load()

    # ------------------------------------------------------------------

    def _load(self) -> None:
        assert self.path is not None
        with self.path.open(encoding="utf-8") as fh:
            for line in fh:
                if line.strip():
                    self._rows.append(Correction(**json.loads(line)))

    def add(
        self,
        raw_text: str,
        corrected: dict,
        predicted: dict | None = None,
        shop_id: str = "default",
    ) -> Correction:
        correction = Correction(
            raw_text=raw_text,
            corrected=corrected,
            predicted=predicted or {},
            shop_id=shop_id,
        )
        self._rows.append(correction)

        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(correction.to_dict(), ensure_ascii=False) + "\n")

        return correction

    # ------------------------------------------------------------------

    def similar(
        self,
        text: str,
        limit: int = 3,
        shop_id: str = "default",
        threshold: float = 0.35,
    ) -> list[Correction]:
        """
        Most similar past corrections for this shop, best first.

        The threshold matters: a weak match is worse than no example at all,
        because an irrelevant few-shot actively misleads the model.
        """
        target = normalise(text)
        scored: list[tuple[float, Correction]] = []

        for row in self._rows:
            if row.shop_id != shop_id:
                continue
            score = token_set_ratio(target, normalise(row.raw_text))
            if score >= threshold:
                scored.append((score, row))

        scored.sort(key=lambda pair: pair[0], reverse=True)
        return [row for _, row in scored[:limit]]

    def all(self, shop_id: str | None = None) -> list[Correction]:
        if shop_id is None:
            return list(self._rows)
        return [r for r in self._rows if r.shop_id == shop_id]

    def field_error_counts(self, shop_id: str | None = None) -> dict[str, int]:
        """
        Which fields get corrected most often.

        Feeds the report directly -- it says where the engine is actually
        weak, measured on real use rather than on the eval set.
        """
        counts: dict[str, int] = {}
        for row in self.all(shop_id):
            for name in row.changed_fields():
                counts[name] = counts.get(name, 0) + 1
        return dict(sorted(counts.items(), key=lambda kv: kv[1], reverse=True))

    def __len__(self) -> int:
        return len(self._rows)
