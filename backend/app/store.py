"""
The ledger store.

SQLite via stdlib `sqlite3` -- no dependency, so the tests still run on a bare
Python. The schema mirrors `docs/api.yaml` exactly; swapping in Postgres later
is a connection change, not a rewrite.

Two things here are load-bearing:

  * Transaction ids are CLIENT-generated UUIDs. That is what makes sync
    idempotent -- replaying a batch after a dropped connection updates rows
    instead of creating a second set. The phone is the source of truth for
    identity, because the phone is where the entry was confirmed.

  * Customers are created on first use and never entered by hand. The khata
    builds itself out of the transactions.
"""

from __future__ import annotations

import random
import sqlite3
import uuid
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS shops (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    language    TEXT NOT NULL DEFAULT 'hi-IN',
    speaker     TEXT NOT NULL DEFAULT 'anushka',
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS customers (
    id          TEXT PRIMARY KEY,
    shop_id     TEXT NOT NULL REFERENCES shops(id),
    name        TEXT NOT NULL,
    phone       TEXT,
    created_at  TEXT NOT NULL,
    UNIQUE (shop_id, name)
);

CREATE TABLE IF NOT EXISTS transactions (
    id           TEXT PRIMARY KEY,          -- client-generated UUID
    shop_id      TEXT NOT NULL REFERENCES shops(id),
    customer_id  TEXT REFERENCES customers(id),
    amount       REAL NOT NULL,
    mode         TEXT NOT NULL,             -- cash | upi | udhaar
    direction    TEXT NOT NULL,             -- sale | payment
    items        TEXT NOT NULL DEFAULT '',  -- comma-separated labels
    source       TEXT NOT NULL,             -- chat|voice|ocr|manual|export
    confidence   REAL NOT NULL DEFAULT 0,
    occurred_at  TEXT NOT NULL,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL,
    deleted      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS ix_tx_shop_time
    ON transactions (shop_id, occurred_at);
CREATE INDEX IF NOT EXISTS ix_tx_customer
    ON transactions (customer_id);
CREATE INDEX IF NOT EXISTS ix_tx_updated
    ON transactions (shop_id, updated_at);
"""


@dataclass
class Transaction:
    shop_id: str
    amount: float
    mode: str
    direction: str = "sale"
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    customer_name: str | None = None
    customer_id: str | None = None
    items: list[str] = field(default_factory=list)
    source: str = "manual"
    confidence: float = 0.0
    occurred_at: str = ""
    deleted: bool = False

    def __post_init__(self) -> None:
        if not self.occurred_at:
            self.occurred_at = datetime.now().isoformat(timespec="seconds")

    @property
    def signed_amount(self) -> float:
        """Positive increases what the customer owes; a payment reduces it."""
        return -self.amount if self.direction == "payment" else self.amount


class LedgerStore:
    def __init__(self, path: str | Path = ":memory:") -> None:
        self.path = str(path)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.executescript(SCHEMA)
        self._conn.commit()

    @contextmanager
    def _tx(self):
        try:
            yield self._conn
            self._conn.commit()
        except Exception:
            self._conn.rollback()
            raise

    def close(self) -> None:
        self._conn.close()

    # ------------------------------------------------------------------
    # Shops and customers
    # ------------------------------------------------------------------

    def create_shop(
        self,
        shop_id: str,
        name: str,
        language: str = "hi-IN",
        speaker: str = "anushka",
    ) -> None:
        with self._tx() as conn:
            conn.execute(
                "INSERT OR IGNORE INTO shops (id, name, language, speaker, created_at)"
                " VALUES (?, ?, ?, ?, ?)",
                (shop_id, name, language, speaker,
                 datetime.now().isoformat(timespec="seconds")),
            )

    def get_shop(self, shop_id: str) -> dict | None:
        row = self._conn.execute(
            "SELECT * FROM shops WHERE id = ?", (shop_id,)
        ).fetchone()
        return dict(row) if row else None

    def upsert_customer(self, shop_id: str, name: str, phone: str | None = None) -> str:
        """
        Get-or-create by name. The list builds itself; nobody types it in.

        Name matching against near-duplicates happens upstream in
        `names.resolve` -- by the time a name reaches here it is already the
        canonical one for this shop.
        """
        row = self._conn.execute(
            "SELECT id FROM customers WHERE shop_id = ? AND name = ?", (shop_id, name)
        ).fetchone()
        if row:
            return row["id"]

        customer_id = str(uuid.uuid4())
        with self._tx() as conn:
            conn.execute(
                "INSERT INTO customers (id, shop_id, name, phone, created_at)"
                " VALUES (?, ?, ?, ?, ?)",
                (customer_id, shop_id, name, phone,
                 datetime.now().isoformat(timespec="seconds")),
            )
        return customer_id

    def customer_names(self, shop_id: str) -> list[str]:
        """Feeds the extraction prompt and the fuzzy matcher."""
        return [
            r["name"] for r in self._conn.execute(
                "SELECT name FROM customers WHERE shop_id = ? ORDER BY name", (shop_id,)
            )
        ]

    # ------------------------------------------------------------------
    # Transactions
    # ------------------------------------------------------------------

    def save(self, tx: Transaction) -> str:
        """
        Insert or update by client-generated id.

        Idempotent by construction: the same id twice is one row. This is what
        lets the phone retry a failed sync without inventing money.
        """
        if tx.customer_id is None and tx.customer_name:
            tx.customer_id = self.upsert_customer(tx.shop_id, tx.customer_name)

        now = datetime.now().isoformat(timespec="seconds")
        with self._tx() as conn:
            conn.execute(
                """
                INSERT INTO transactions
                    (id, shop_id, customer_id, amount, mode, direction, items,
                     source, confidence, occurred_at, created_at, updated_at, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    amount      = excluded.amount,
                    mode        = excluded.mode,
                    direction   = excluded.direction,
                    items       = excluded.items,
                    confidence  = excluded.confidence,
                    occurred_at = excluded.occurred_at,
                    updated_at  = excluded.updated_at,
                    deleted     = excluded.deleted
                """,
                (tx.id, tx.shop_id, tx.customer_id, tx.amount, tx.mode, tx.direction,
                 ",".join(tx.items), tx.source, tx.confidence, tx.occurred_at,
                 now, now, int(tx.deleted)),
            )
        return tx.id

    def save_many(self, transactions: list[Transaction]) -> tuple[int, int]:
        """Returns (accepted, duplicates) -- duplicates are ids already present."""
        existing = {
            r["id"] for r in self._conn.execute(
                "SELECT id FROM transactions"
            )
        }
        duplicates = sum(1 for t in transactions if t.id in existing)
        for tx in transactions:
            self.save(tx)
        return len(transactions) - duplicates, duplicates

    def changed_since(self, shop_id: str, since: str | None) -> list[dict]:
        sql = "SELECT * FROM transactions WHERE shop_id = ?"
        params: list = [shop_id]
        if since:
            sql += " AND updated_at > ?"
            params.append(since)
        sql += " ORDER BY updated_at"
        return [dict(r) for r in self._conn.execute(sql, params)]

    def soft_delete(self, tx_id: str) -> None:
        """Undo. Never a hard delete -- the audit trail is the point."""
        with self._tx() as conn:
            conn.execute(
                "UPDATE transactions SET deleted = 1, updated_at = ? WHERE id = ?",
                (datetime.now().isoformat(timespec="seconds"), tx_id),
            )

    # ------------------------------------------------------------------
    # Aggregates
    # ------------------------------------------------------------------

    def _live(self) -> str:
        return "deleted = 0"

    def day_totals(self, shop_id: str, on: date | None = None) -> dict:
        day = (on or date.today()).isoformat()
        row = self._conn.execute(
            f"""
            SELECT
              COALESCE(SUM(CASE WHEN direction='sale' THEN amount END), 0)          AS sale,
              COALESCE(SUM(CASE WHEN direction='sale' AND mode IN ('cash','upi')
                                THEN amount END), 0)                                AS cash_in,
              COALESCE(SUM(CASE WHEN direction='sale' AND mode='udhaar'
                                THEN amount END), 0)                                AS udhaar,
              COUNT(*)                                                              AS entries,
              COALESCE(SUM(CASE WHEN confidence >= 0.85 THEN 1 ELSE 0 END), 0)       AS auto_filled
            FROM transactions
            WHERE shop_id = ? AND {self._live()} AND date(occurred_at) = ?
            """,
            (shop_id, day),
        ).fetchone()
        return dict(row)

    def outstanding(self, shop_id: str) -> list[dict]:
        """
        Per-customer balance, worst first.

        `days_overdue` is computed at read time from the oldest unsettled
        activity, never stored -- a stored value is wrong the next morning.
        """
        rows = self._conn.execute(
            f"""
            SELECT
              c.id, c.name,
              COALESCE(SUM(CASE WHEN t.direction='sale' AND t.mode='udhaar' THEN t.amount
                                WHEN t.direction='payment' THEN -t.amount
                                ELSE 0 END), 0)              AS outstanding,
              MAX(t.occurred_at)                             AS last_activity,
              MIN(CASE WHEN t.direction='sale' AND t.mode='udhaar'
                       THEN t.occurred_at END)               AS oldest_udhaar
            FROM customers c
            LEFT JOIN transactions t
              ON t.customer_id = c.id AND t.{self._live()}
            WHERE c.shop_id = ?
            GROUP BY c.id, c.name
            ORDER BY outstanding DESC, c.name
            """,
            (shop_id,),
        ).fetchall()

        today = date.today()
        result = []
        for row in rows:
            entry = dict(row)
            entry["outstanding"] = round(entry["outstanding"] or 0, 2)
            entry["days_overdue"] = 0
            if entry["outstanding"] > 0 and entry["oldest_udhaar"]:
                oldest = datetime.fromisoformat(entry["oldest_udhaar"]).date()
                entry["days_overdue"] = (today - oldest).days
            entry.pop("oldest_udhaar", None)
            result.append(entry)
        return result

    def series(self, shop_id: str, days: int = 14) -> list[dict]:
        """Daily cash-vs-udhaar for the dashboard chart. Gaps filled with zeros."""
        start = date.today() - timedelta(days=days - 1)
        rows = {
            r["d"]: r for r in self._conn.execute(
                f"""
                SELECT date(occurred_at) AS d,
                  COALESCE(SUM(CASE WHEN mode IN ('cash','upi') AND direction='sale'
                                    THEN amount END), 0) AS cash,
                  COALESCE(SUM(CASE WHEN mode='udhaar' AND direction='sale'
                                    THEN amount END), 0) AS udhaar
                FROM transactions
                WHERE shop_id = ? AND {self._live()} AND date(occurred_at) >= ?
                GROUP BY date(occurred_at)
                """,
                (shop_id, start.isoformat()),
            )
        }

        out = []
        for offset in range(days):
            day = (start + timedelta(days=offset)).isoformat()
            row = rows.get(day)
            out.append({
                "date": day,
                "cash": round(row["cash"], 2) if row else 0.0,
                "udhaar": round(row["udhaar"], 2) if row else 0.0,
            })
        return out

    def summary(self, shop_id: str) -> dict:
        """Everything the dashboard and the spoken summary both read from."""
        totals = self.day_totals(shop_id)
        balances = self.outstanding(shop_id)
        return {
            "shop": self.get_shop(shop_id),
            "today_sale": round(totals["sale"], 2),
            "cash_in": round(totals["cash_in"], 2),
            "udhaar_today": round(totals["udhaar"], 2),
            "entry_count": totals["entries"],
            "auto_filled": totals["auto_filled"],
            "total_outstanding": round(sum(b["outstanding"] for b in balances), 2),
            "overdue": [b for b in balances if b["days_overdue"] > 30],
            "customers": balances,
            "series": self.series(shop_id),
        }

    # ------------------------------------------------------------------
    # Demo data
    # ------------------------------------------------------------------

    def seed(self, shop_id: str = "sharma-kirana", days: int = 90) -> dict:
        """
        Three months of plausible history.

        Never demo on an empty database -- an empty dashboard shows nothing
        about whether the dashboard works.
        """
        self.create_shop(shop_id, "Sharma Kirana Store")

        people = [
            ("Rahul Verma", "+919800000432"),
            ("Priya Sharma", "+919800000178"),
            ("Imran Khan", "+919800000915"),
            ("Sunita Devi", "+919800000264"),
            ("Mohan Lal", "+919800000731"),
        ]
        for name, phone in people:
            self.upsert_customer(shop_id, name, phone)

        goods = [
            "chawal 2kg", "atta 5kg", "chai 1packet", "tel 1l", "cheeni 1kg",
            "dal 2kg", "namak 1packet", "doodh 2l", "sabun 3pc", "biscuit 4packet",
        ]
        rng = random.Random(42)  # deterministic, so the demo looks the same twice
        created = 0

        for offset in range(days):
            day = date.today() - timedelta(days=days - 1 - offset)
            for _ in range(rng.randint(3, 9)):
                name, _phone = rng.choice(people)
                mode = rng.choices(["cash", "upi", "udhaar"], weights=[5, 3, 2])[0]
                when = datetime.combine(day, datetime.min.time()) + timedelta(
                    hours=rng.randint(8, 20), minutes=rng.randint(0, 59)
                )
                self.save(Transaction(
                    shop_id=shop_id,
                    customer_name=name,
                    amount=float(rng.choice([40, 60, 80, 120, 150, 210, 250, 340, 450])),
                    mode=mode,
                    direction="sale",
                    items=rng.sample(goods, rng.randint(1, 3)),
                    source=rng.choices(
                        ["chat", "voice", "ocr", "manual", "export"],
                        weights=[5, 3, 1, 2, 2],
                    )[0],
                    confidence=round(rng.uniform(0.55, 0.99), 2),
                    occurred_at=when.isoformat(timespec="seconds"),
                ))
                created += 1

            # Customers settle up now and then.
            if rng.random() < 0.35:
                name, _phone = rng.choice(people)
                self.save(Transaction(
                    shop_id=shop_id,
                    customer_name=name,
                    amount=float(rng.choice([200, 300, 500, 800])),
                    mode=rng.choice(["cash", "upi"]),
                    direction="payment",
                    source="manual",
                    confidence=1.0,
                    occurred_at=datetime.combine(
                        day, datetime.min.time()
                    ).replace(hour=19).isoformat(timespec="seconds"),
                ))
                created += 1

        return {"shop_id": shop_id, "transactions": created, "customers": len(people)}
