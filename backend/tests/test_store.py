"""Tests for the ledger store, the summary composer, and the TTS cache."""

import sys
import tempfile
import unittest
from datetime import date, datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.store import LedgerStore, Transaction  # noqa: E402
from app.summary import compose  # noqa: E402
from app.tts import SarvamTTS  # noqa: E402

SHOP = "test-shop"


def _tx(**kwargs) -> Transaction:
    defaults = dict(shop_id=SHOP, amount=100.0, mode="cash", direction="sale")
    defaults.update(kwargs)
    return Transaction(**defaults)


class StoreCase(unittest.TestCase):
    def setUp(self):
        self.store = LedgerStore(":memory:")
        self.store.create_shop(SHOP, "Sharma Kirana Store")

    def tearDown(self):
        self.store.close()


class TestIdempotency(StoreCase):
    """Client-generated ids are what make a retried sync safe."""

    def test_same_id_twice_is_one_row(self):
        tx = _tx(customer_name="Rahul Verma", amount=250, mode="udhaar")
        self.store.save(tx)
        self.store.save(tx)
        self.assertEqual(len(self.store.changed_since(SHOP, None)), 1)

    def test_replayed_batch_reports_duplicates(self):
        batch = [_tx(customer_name="Rahul Verma") for _ in range(3)]
        accepted, duplicates = self.store.save_many(batch)
        self.assertEqual((accepted, duplicates), (3, 0))

        accepted, duplicates = self.store.save_many(batch)
        self.assertEqual((accepted, duplicates), (0, 3))
        self.assertEqual(len(self.store.changed_since(SHOP, None)), 3)

    def test_resend_updates_rather_than_duplicates(self):
        tx = _tx(customer_name="Rahul Verma", amount=250)
        self.store.save(tx)
        tx.amount = 300
        self.store.save(tx)

        rows = self.store.changed_since(SHOP, None)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["amount"], 300)


class TestCustomers(StoreCase):
    def test_list_builds_itself_from_transactions(self):
        self.store.save(_tx(customer_name="Rahul Verma"))
        self.store.save(_tx(customer_name="Priya Sharma"))
        self.assertEqual(
            self.store.customer_names(SHOP), ["Priya Sharma", "Rahul Verma"]
        )

    def test_same_name_reuses_one_customer(self):
        first = self.store.upsert_customer(SHOP, "Rahul Verma")
        second = self.store.upsert_customer(SHOP, "Rahul Verma")
        self.assertEqual(first, second)


class TestBalances(StoreCase):
    def test_udhaar_adds_and_payment_subtracts(self):
        self.store.save(_tx(customer_name="Rahul Verma", amount=250, mode="udhaar"))
        self.store.save(_tx(customer_name="Rahul Verma", amount=500, mode="udhaar"))
        self.store.save(
            _tx(customer_name="Rahul Verma", amount=300, mode="upi", direction="payment")
        )

        balance = next(
            c for c in self.store.outstanding(SHOP) if c["name"] == "Rahul Verma"
        )
        self.assertEqual(balance["outstanding"], 450)

    def test_cash_sale_creates_no_debt(self):
        self.store.save(_tx(customer_name="Priya Sharma", amount=200, mode="cash"))
        balance = next(
            c for c in self.store.outstanding(SHOP) if c["name"] == "Priya Sharma"
        )
        self.assertEqual(balance["outstanding"], 0)

    def test_days_overdue_is_computed_from_oldest_udhaar(self):
        old = (datetime.now() - timedelta(days=42)).isoformat(timespec="seconds")
        self.store.save(_tx(
            customer_name="Sunita Devi", amount=840, mode="udhaar", occurred_at=old
        ))
        balance = next(
            c for c in self.store.outstanding(SHOP) if c["name"] == "Sunita Devi"
        )
        self.assertEqual(balance["days_overdue"], 42)

    def test_payments_settle_oldest_debt_first(self):
        """
        Regression: aging must follow the money, not the calendar.

        Taking the oldest udhaar date outright ages a balance from a debt the
        customer already paid off, so every regular customer reads as
        permanently overdue and the flag stops meaning anything -- and the
        reminder it drives is plainly wrong to whoever receives it.
        """
        def days_ago(n):
            return (datetime.now() - timedelta(days=n)).isoformat(timespec="seconds")

        # An old debt, fully paid, then a fresh one.
        self.store.save(_tx(customer_name="Priya Sharma", amount=500,
                            mode="udhaar", occurred_at=days_ago(80)))
        self.store.save(_tx(customer_name="Priya Sharma", amount=500,
                            mode="cash", direction="payment",
                            occurred_at=days_ago(70)))
        self.store.save(_tx(customer_name="Priya Sharma", amount=300,
                            mode="udhaar", occurred_at=days_ago(10)))

        balance = next(
            c for c in self.store.outstanding(SHOP) if c["name"] == "Priya Sharma"
        )
        self.assertEqual(balance["outstanding"], 300)
        # 10, from the unpaid debt -- not 80 from the settled one.
        self.assertEqual(balance["days_overdue"], 10)

    def test_partial_payment_ages_from_the_remaining_debt(self):
        def days_ago(n):
            return (datetime.now() - timedelta(days=n)).isoformat(timespec="seconds")

        self.store.save(_tx(customer_name="Imran Khan", amount=400,
                            mode="udhaar", occurred_at=days_ago(50)))
        self.store.save(_tx(customer_name="Imran Khan", amount=600,
                            mode="udhaar", occurred_at=days_ago(20)))
        # Clears the first debt and 100 of the second.
        self.store.save(_tx(customer_name="Imran Khan", amount=500,
                            mode="upi", direction="payment",
                            occurred_at=days_ago(5)))

        balance = next(
            c for c in self.store.outstanding(SHOP) if c["name"] == "Imran Khan"
        )
        self.assertEqual(balance["outstanding"], 500)
        self.assertEqual(balance["days_overdue"], 20)

    def test_overpayment_floors_at_zero(self):
        self.store.save(_tx(customer_name="Mohan Lal", amount=200, mode="udhaar"))
        self.store.save(_tx(customer_name="Mohan Lal", amount=500,
                            mode="cash", direction="payment"))
        balance = next(
            c for c in self.store.outstanding(SHOP) if c["name"] == "Mohan Lal"
        )
        # Advances are not modelled; the balance does not go negative.
        self.assertEqual(balance["outstanding"], 0)

    def test_seed_produces_a_believable_overdue_spread(self):
        """Every customer at ~87 days means the flag is broken, not the shop."""
        self.store.seed(SHOP, days=90)
        overdue = [c["days_overdue"] for c in self.store.outstanding(SHOP)
                   if c["outstanding"] > 0]
        self.assertTrue(overdue)
        self.assertLess(min(overdue), 30, "no recent balances — aging looks stuck")
        self.assertLess(max(overdue), 90)

    def test_settled_customer_is_not_overdue(self):
        old = (datetime.now() - timedelta(days=60)).isoformat(timespec="seconds")
        self.store.save(_tx(customer_name="Mohan Lal", amount=500, mode="udhaar",
                            occurred_at=old))
        self.store.save(_tx(customer_name="Mohan Lal", amount=500, mode="cash",
                            direction="payment"))
        balance = next(
            c for c in self.store.outstanding(SHOP) if c["name"] == "Mohan Lal"
        )
        self.assertEqual(balance["outstanding"], 0)
        self.assertEqual(balance["days_overdue"], 0)


class TestSoftDelete(StoreCase):
    def test_undo_removes_from_totals_but_keeps_the_row(self):
        tx = _tx(customer_name="Rahul Verma", amount=250, mode="udhaar")
        self.store.save(tx)
        self.store.soft_delete(tx.id)

        self.assertEqual(self.store.day_totals(SHOP)["sale"], 0)
        # The audit trail is the point -- the row survives.
        self.assertEqual(len(self.store.changed_since(SHOP, None)), 1)


class TestAggregates(StoreCase):
    def test_day_totals_split_cash_and_credit(self):
        self.store.save(_tx(customer_name="A", amount=100, mode="cash"))
        self.store.save(_tx(customer_name="B", amount=200, mode="upi"))
        self.store.save(_tx(customer_name="C", amount=300, mode="udhaar"))

        totals = self.store.day_totals(SHOP)
        self.assertEqual(totals["sale"], 600)
        self.assertEqual(totals["cash_in"], 300)
        self.assertEqual(totals["udhaar"], 300)

    def test_series_fills_empty_days_with_zero(self):
        series = self.store.series(SHOP, days=14)
        self.assertEqual(len(series), 14)
        self.assertEqual(series[-1]["date"], date.today().isoformat())
        self.assertTrue(all("cash" in row for row in series))

    def test_auto_filled_counts_high_confidence_entries(self):
        self.store.save(_tx(customer_name="A", confidence=0.95))
        self.store.save(_tx(customer_name="B", confidence=0.60))
        self.assertEqual(self.store.day_totals(SHOP)["auto_filled"], 1)


class TestSeed(StoreCase):
    def test_seed_produces_a_usable_demo(self):
        result = self.store.seed(SHOP, days=30)
        self.assertGreater(result["transactions"], 50)

        summary = self.store.summary(SHOP)
        self.assertEqual(len(summary["customers"]), 5)
        self.assertEqual(len(summary["series"]), 14)
        self.assertGreater(summary["total_outstanding"], 0)

    def test_seed_is_deterministic(self):
        a = LedgerStore(":memory:")
        b = LedgerStore(":memory:")
        self.assertEqual(a.seed(SHOP, days=20), b.seed(SHOP, days=20))
        a.close()
        b.close()


class TestPersistence(unittest.TestCase):
    def test_survives_reopen(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "ledger.db"
            first = LedgerStore(path)
            first.create_shop(SHOP, "Sharma Kirana Store")
            first.save(_tx(customer_name="Rahul Verma", amount=250, mode="udhaar"))
            first.close()

            second = LedgerStore(path)
            self.assertEqual(second.customer_names(SHOP), ["Rahul Verma"])
            second.close()


class TestNarration(StoreCase):
    def setUp(self):
        super().setUp()
        self.store.save(_tx(customer_name="Rahul Verma", amount=4000, mode="cash"))
        self.store.save(_tx(customer_name="Priya Sharma", amount=240, mode="udhaar"))
        self.summary = self.store.summary(SHOP)

    def test_amounts_are_spelled_out_for_speech(self):
        n = compose(self.summary, "hi-IN")
        self.assertIn("chaar hazaar do sau chalees", n.text)
        # A bare numeral would be read inconsistently by the voice model.
        self.assertNotIn("4240", n.text)

    def test_english_keeps_digits(self):
        n = compose(self.summary, "en-IN")
        self.assertIn("4,240 rupees", n.text)

    def test_shop_name_is_used(self):
        self.assertIn("Sharma Kirana Store", compose(self.summary, "hi-IN").text)

    def test_unknown_language_falls_back_to_hindi(self):
        self.assertEqual(compose(self.summary, "fr-FR").language_code, "hi-IN")

    def test_marathi_renders(self):
        self.assertIn("vikri", compose(self.summary, "mr-IN").text)

    def test_fits_inside_the_bulbul_character_cap(self):
        self.store.seed(SHOP, days=90)
        for language in ("hi-IN", "en-IN", "mr-IN"):
            with self.subTest(language=language):
                self.assertLess(len(compose(self.store.summary(SHOP), language)), 2500)

    def test_quiet_day_says_so(self):
        empty = LedgerStore(":memory:")
        empty.create_shop("quiet", "Test Shop")
        n = compose(empty.summary("quiet"), "hi-IN")
        self.assertIn("koi entry nahi", n.text)
        empty.close()

    def test_overdue_customer_is_mentioned_last(self):
        old = (datetime.now() - timedelta(days=42)).isoformat(timespec="seconds")
        self.store.save(_tx(customer_name="Sunita Devi", amount=840,
                            mode="udhaar", occurred_at=old))
        n = compose(self.store.summary(SHOP), "hi-IN")
        self.assertIn("Sunita Devi", n.text)
        # It is the actionable part, so it must survive to the end.
        self.assertGreater(n.text.index("Sunita Devi"), n.text.index("sale"))


class TestTTSCache(unittest.TestCase):
    def test_key_changes_with_every_input(self):
        base = SarvamTTS.cache_key("hello", "hi-IN", "anushka", "bulbul:v3")
        self.assertNotEqual(base, SarvamTTS.cache_key("hello", "en-IN", "anushka", "bulbul:v3"))
        self.assertNotEqual(base, SarvamTTS.cache_key("hello", "hi-IN", "priya", "bulbul:v3"))
        self.assertNotEqual(base, SarvamTTS.cache_key("hi", "hi-IN", "anushka", "bulbul:v3"))

    def test_key_is_stable(self):
        self.assertEqual(
            SarvamTTS.cache_key("aaj ki sale", "hi-IN", "anushka", "bulbul:v3"),
            SarvamTTS.cache_key("aaj ki sale", "hi-IN", "anushka", "bulbul:v3"),
        )

    def test_reports_unavailable_without_a_key(self):
        self.assertFalse(SarvamTTS(api_key=None).available())
        self.assertTrue(SarvamTTS(api_key="test").available())

    def test_cache_hit_needs_no_key(self):
        """A cached day must still play when the network or key is gone."""
        with tempfile.TemporaryDirectory() as tmp:
            tts = SarvamTTS(api_key=None, cache_dir=tmp)
            path = tts.cached_path("aaj ki sale", "hi-IN", "anushka")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fake-mp3")

            speech = tts.synthesize("aaj ki sale", "hi-IN", "anushka")
            self.assertTrue(speech.cache_hit)

    def test_missing_key_raises_rather_than_silently_failing(self):
        with tempfile.TemporaryDirectory() as tmp:
            tts = SarvamTTS(api_key=None, cache_dir=tmp)
            with self.assertRaises(RuntimeError):
                tts.synthesize("uncached text", "hi-IN", "anushka")

    def test_rejects_text_over_the_api_cap(self):
        tts = SarvamTTS(api_key="test")
        with self.assertRaises(ValueError):
            tts.synthesize("x" * 2501)

    def test_rejects_unknown_speaker(self):
        tts = SarvamTTS(api_key="test")
        with self.assertRaises(ValueError):
            tts.synthesize("hello", "hi-IN", speaker="nonexistent")


if __name__ == "__main__":
    unittest.main(verbosity=2)
