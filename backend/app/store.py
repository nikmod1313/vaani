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
import json
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS shops (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    language    TEXT NOT NULL DEFAULT 'hi-IN',
    speaker     TEXT NOT NULL DEFAULT 'anushka',
    category    TEXT NOT NULL DEFAULT 'general',
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

-- The shop's price list, as the phone keeps it: one JSON list, last writer
-- wins by updated_at (epoch millis, the phone's clock or the dashboard's).
CREATE TABLE IF NOT EXISTS catalogues (
    shop_id     TEXT PRIMARY KEY,
    items       TEXT NOT NULL DEFAULT '[]',
    updated_at  INTEGER NOT NULL DEFAULT 0
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
        self._migrate()
        self._conn.commit()

    def _migrate(self) -> None:
        """
        Additive column migrations.

        `CREATE TABLE IF NOT EXISTS` is a no-op on an existing table, so a
        database created before a column existed never grows it -- and the
        first SELECT naming that column fails on a store that has been
        working for weeks. Adding columns here keeps old ledgers readable.

        Only ever ADD. Dropping or retyping a column in a file that holds
        someone's khata needs a real migration with a backup, not a line in
        this list.
        """
        additive = [
            ("shops", "category", "TEXT NOT NULL DEFAULT 'general'"),
        ]
        for table, column, spec in additive:
            existing = {
                row["name"]
                for row in self._conn.execute(f"PRAGMA table_info({table})")
            }
            if column not in existing:
                self._conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {spec}")

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
        category: str | None = None,
    ) -> None:
        """
        Create, or update the name of an existing shop.

        `category` is only written when supplied. A sync that does not carry
        one must not reset a shop that has already chosen -- the phone sends
        this on setup, not on every push.
        """
        with self._tx() as conn:
            conn.execute(
                """
                INSERT INTO shops (id, name, language, speaker, category, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name     = excluded.name,
                    category = COALESCE(?, shops.category)
                """,
                (shop_id, name, language, speaker, category or "general",
                 datetime.now().isoformat(timespec="seconds"), category),
            )

    def _ensure_shop(self, shop_id: str) -> None:
        """
        Guarantee a shop row exists before anything references it.

        Without this a fresh phone's first sync writes transactions and
        customers for a shop that has no row, so /summary and
        /summary/narrate both 404 on a shop that demonstrably has data --
        which reads as data loss to whoever is looking at the dashboard.

        The placeholder name is replaced the moment the client sends a real
        one, via create_shop's upsert above.
        """
        with self._tx() as conn:
            conn.execute(
                "INSERT OR IGNORE INTO shops (id, name, language, speaker, created_at)"
                " VALUES (?, ?, ?, ?, ?)",
                (shop_id, shop_id, "hi-IN", "anushka",
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
        self._ensure_shop(shop_id)
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
        self._ensure_shop(tx.shop_id)
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
        # The customer's name rides along: a phone pulling a dashboard entry
        # has no other way to know whose khata it belongs to.
        sql = ("SELECT t.*, c.name AS customer_name FROM transactions t "
               "LEFT JOIN customers c ON c.id = t.customer_id WHERE t.shop_id = ?")
        params: list = [shop_id]
        if since:
            sql += " AND t.updated_at > ?"
            params.append(since)
        sql += " ORDER BY t.updated_at"
        return [dict(r) for r in self._conn.execute(sql, params)]

    def transactions(
        self,
        shop_id: str,
        q: str = "",
        direction: str | None = None,
        customer: str | None = None,
        limit: int = 200,
    ) -> list[dict]:
        """The dashboard's searchable khata, newest first."""
        sql = (
            "SELECT t.id, t.amount, t.mode, t.direction, t.items, t.source, "
            "t.occurred_at, c.name AS customer_name FROM transactions t "
            "LEFT JOIN customers c ON c.id = t.customer_id "
            f"WHERE t.shop_id = ? AND t.{self._live()}"
        )
        params: list = [shop_id]
        if direction in ("sale", "payment"):
            sql += " AND t.direction = ?"
            params.append(direction)
        if customer:
            sql += " AND LOWER(c.name) = LOWER(?)"
            params.append(customer)
        if q.strip():
            sql += " AND (LOWER(COALESCE(c.name, '')) LIKE ? OR LOWER(t.items) LIKE ? OR CAST(t.amount AS TEXT) LIKE ?)"
            like = f"%{q.strip().lower()}%"
            params += [like, like, like]
        sql += " ORDER BY t.occurred_at DESC LIMIT ?"
        params.append(max(1, min(limit, 1000)))
        return [dict(r) for r in self._conn.execute(sql, params)]

    def top_items(self, shop_id: str, days: int = 30, limit: int = 10) -> list[dict]:
        """What sold, by how often it appears on a bill. Labels carry a trailing quantity ("kurti 2"), stripped here."""
        since = (date.today() - timedelta(days=days - 1)).isoformat()
        counts: dict[str, dict] = {}
        for row in self._conn.execute(
            f"SELECT items FROM transactions WHERE shop_id = ? AND {self._live()} "
            "AND direction = 'sale' AND items != '' AND date(occurred_at) >= ?",
            (shop_id, since),
        ):
            for label in row["items"].split(","):
                parts = label.strip().rsplit(" ", 1)
                qty = 1.0
                name = label.strip()
                if len(parts) == 2:
                    num = "".join(ch for ch in parts[1] if ch.isdigit() or ch == ".")
                    if num and parts[1][0].isdigit():
                        name, qty = parts[0], float(num) if num.count(".") <= 1 else 1.0
                if not name:
                    continue
                key = name.lower()
                entry = counts.setdefault(key, {"name": name, "bills": 0, "quantity": 0.0})
                entry["bills"] += 1
                entry["quantity"] += qty
        ranked = sorted(counts.values(), key=lambda e: (-e["bills"], e["name"]))
        return ranked[:limit]

    def catalogue(self, shop_id: str) -> dict:
        row = self._conn.execute(
            "SELECT items, updated_at FROM catalogues WHERE shop_id = ?", (shop_id,)
        ).fetchone()
        if not row:
            return {"items": [], "updated_at": 0}
        return {"items": json.loads(row["items"]), "updated_at": row["updated_at"]}

    def put_catalogue(self, shop_id: str, items: list[dict], updated_at: int) -> dict:
        """Keep the newer list. Returns whichever one won, for the caller to adopt."""
        current = self.catalogue(shop_id)
        if updated_at > current["updated_at"]:
            self._ensure_shop(shop_id)
            with self._tx() as conn:
                conn.execute(
                    "INSERT INTO catalogues (shop_id, items, updated_at) VALUES (?, ?, ?) "
                    "ON CONFLICT(shop_id) DO UPDATE SET items = excluded.items, updated_at = excluded.updated_at",
                    (shop_id, json.dumps(items, ensure_ascii=False), updated_at),
                )
            return {"items": items, "updated_at": updated_at}
        return current

    def keep_only(self, shop_id: str, ids: set[str]) -> int:
        """
        Make this shop hold exactly these rows (plus dashboard entries the
        phone has not pulled yet). Soft, like every delete here.

        Without it a phone paired to a server that already had rows under
        its shop id -- demo data, an old test phone -- showed the sum of
        both, and every figure on the dashboard was wrong.
        """
        now = datetime.now().isoformat(timespec="seconds")
        stale = [
            r["id"] for r in self._conn.execute(
                f"SELECT id FROM transactions WHERE shop_id = ? AND {self._live()} AND source != 'dashboard'",
                (shop_id,),
            ) if r["id"] not in ids
        ]
        with self._tx() as conn:
            conn.executemany(
                "UPDATE transactions SET deleted = 1, updated_at = ? WHERE id = ?",
                [(now, i) for i in stale],
            )
        return len(stale)

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
        Per-customer balance and the age of that balance, worst first.

        Payments settle debts oldest-first (FIFO), which is how a shopkeeper
        actually thinks about a khata. That matters for `days_overdue`: taking
        the oldest udhaar date outright would age a balance from a debt the
        customer paid off months ago, so every regular customer looks
        permanently overdue and the flag stops meaning anything -- and the
        reminder it drives would be plainly wrong to the person receiving it.

        Computed at read time, never stored. A stored value is wrong tomorrow.

        An advance is carried. Paying more than is owed -- or paying before a
        purchase, which a regular does with a few hundred rupees "jama" --
        leaves a credit that the next udhaar draws down. Dropping it, as this
        used to, overstated what the customer owed by exactly what they had
        already paid. The balance shown still floors at zero.
        """
        rows = self._conn.execute(
            f"""
            SELECT c.id, c.name,
                   t.amount, t.mode, t.direction, t.occurred_at
            FROM customers c
            LEFT JOIN transactions t
              ON t.customer_id = c.id AND t.{self._live()}
            WHERE c.shop_id = ?
            ORDER BY c.name, t.occurred_at
            """,
            (shop_id,),
        ).fetchall()

        people: dict[str, dict] = {}
        for row in rows:
            person = people.setdefault(row["id"], {
                "id": row["id"],
                "name": row["name"],
                "debts": [],          # [[occurred_at, remaining], ...] oldest first
                "advance": 0.0,       # paid and not yet spent against a debt
                "last_activity": None,
            })
            if row["occurred_at"] is None:
                continue

            if row["occurred_at"] > (person["last_activity"] or ""):
                person["last_activity"] = row["occurred_at"]

            if row["direction"] == "sale" and row["mode"] == "udhaar":
                owed = row["amount"]
                used = min(owed, person["advance"])
                person["advance"] -= used
                if owed - used > 0:
                    person["debts"].append([row["occurred_at"], owed - used])
            elif row["direction"] == "payment":
                credit = row["amount"]
                while credit > 0 and person["debts"]:
                    oldest = person["debts"][0]
                    if oldest[1] <= credit:
                        credit -= oldest[1]
                        person["debts"].pop(0)
                    else:
                        oldest[1] -= credit
                        credit = 0
                person["advance"] += credit

        today = date.today()
        result = []
        for person in people.values():
            debts = person["debts"]
            balance = round(sum(d[1] for d in debts), 2)
            days = 0
            if balance > 0 and debts:
                days = (today - datetime.fromisoformat(debts[0][0]).date()).days
            result.append({
                "id": person["id"],
                "name": person["name"],
                "outstanding": balance,
                "last_activity": person["last_activity"],
                "days_overdue": days,
            })

        result.sort(key=lambda c: (-c["outstanding"], c["name"]))
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

    def summary(self, shop_id: str, days: int = 14) -> dict:
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
            "series": self.series(shop_id, days),
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
