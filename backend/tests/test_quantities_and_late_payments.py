"""
Two field reports.

1. "Shyam ne do kurti kharide do hazar mein" -- a count word must count the
   item, not become part of its name or the bill.
2. "Payment done" fifty minutes after an order booked on credit must pay that
   order, not stand as a separate entry while the order stays udhaar.
"""

import unittest
from datetime import datetime, timedelta

from app.extraction.conversation import Turn, resolve_all
from app.extraction.pipeline import extract
from app.extraction.rules import normalise_label
from app.extraction.whatsapp import ChatCursor, ingest_conversation

T0 = datetime(2026, 10, 30, 10, 0)


def at(minute, body, shop=False):
    return Turn(
        sender="Gupta Store" if shop else "Rahul Verma",
        body=body,
        timestamp=T0 + timedelta(minutes=minute),
        is_shopkeeper=shop,
        content_hash=f"h{minute}",
    )


class TestCountWords(unittest.TestCase):
    def _lines(self, text, category="apparel"):
        r = extract(text, category=category).to_dict()
        return r["amount"], [(li["name"], li["quantity"]) for li in r["line_items"]]

    def test_the_reported_sentence(self):
        self.assertEqual(
            self._lines("Shyam ne do kurti kharide do hazar mein."),
            (2000.0, [("kurti", 2.0)]),
        )

    def test_across_hindi_hinglish_and_digits(self):
        for text in [
            "Shyam ne do kurti kharide 2000 mein",
            "श्याम ने दो कुर्ती खरीदे दो हजार में",
            "do kurti do hazar",
            "2 kurti 2000",
            "Shyam ne 2 kurti kharide ₹2,000 mein",
        ]:
            amount, lines = self._lines(text)
            self.assertEqual(amount, 2000.0, text)
            self.assertEqual(len(lines), 1, text)
            self.assertEqual(lines[0][1], 2.0, text)

    def test_teen_and_chaar(self):
        self.assertEqual(self._lines("ramesh ne teen saree li paanch hazar ki"), (5000.0, [("saree", 3.0)]))
        self.assertEqual(self._lines("chaar packet biscuit sau rupaye", "kirana"), (100.0, [("biscuit", 4.0)]))

    def test_hindi_plurals_name_the_item(self):
        self.assertEqual(self._lines("shyam ne do kurtiyan kharidi do hazaar mein"), (2000.0, [("kurti", 2.0)]))
        amount, lines = self._lines("श्याम ने दो कुर्तियाँ खरीदीं दो हजार में")
        self.assertEqual((amount, lines[0][1]), (2000.0, 2.0))

    def test_a_count_next_to_udhaar_is_not_the_bill(self):
        """The count read as money: Rs 2 booked for a Rs 2,000 credit."""
        for text in [
            "Shyam ne do kurti kharidi, do hazar udhaar",
            "do saree udhaar 3000",
            "2 kurti udhaar 3000",
        ]:
            amount, lines = self._lines(text)
            self.assertGreaterEqual(amount, 2000.0, text)
            self.assertEqual(lines[0][1], 2.0, text)

    def test_the_old_adjacency_cases_still_hold(self):
        for text, amount in [
            ("dhai sau ka udhaar", 250.0),
            ("2 kilo chawal aur 1 packet chai, dhai sau ka udhaar likh lena", 250.0),
            ("500 ka udhaar", 500.0),
            ("₹250", 250.0),
        ]:
            self.assertEqual(extract(text, category="kirana").amount, amount, text)


class TestMediaIsIgnored(unittest.TestCase):
    """A photo, voice note or document in the chat is not part of the order."""

    def test_file_names_sizes_and_placeholders_carry_no_numbers(self):
        for text, amount, qty in [
            ("IMG-20261030-WA0004.jpg 245 kB 2 kurti bhej do 500 each", 1000.0, 2.0),
            ("PDF • 2 pages • 1.2 MB  bill.pdf  1500 ka udhaar", 1500.0, None),
            ("<Media omitted> 3 saree chahiye", None, 3.0),
            ("VID-20261030-WA0001.mp4 (file attached) 2 kurti 1000", 1000.0, 2.0),
        ]:
            r = extract(text, category="apparel").to_dict()
            self.assertEqual(r["amount"], amount, text)
            if qty is not None:
                self.assertEqual(r["line_items"][0]["quantity"], qty, text)

    def test_media_alone_is_nothing(self):
        for text in ["245 kB", "1.2 MB", "IMG-20261030-WA0004.jpg", "‎image omitted", "<Media omitted>"]:
            r = extract(text, category="apparel")
            self.assertIsNone(r.amount, text)
            self.assertEqual(r.items, [], text)

    def test_gigabytes_are_an_item_not_a_download(self):
        """An electronics shop sells a 64 GB pendrive; that is not a file size."""
        from app.extraction.numbers import normalise
        self.assertIn("64", normalise("64 GB pendrive 2 chahiye"))


class TestImperativeDoIsNotARate(unittest.TestCase):
    """ "bhej do 500 each" made the rate 502 and the bill Rs 1004."""

    def test_the_verb_is_not_added_to_the_price(self):
        for text, amount in [
            ("2 kurti bhej do 500 each", 1000.0), ("2 kurti de do 500 each", 1000.0),
            ("3 saree bhej do 800 each", 2400.0), ("do kurti 500 each", 1000.0),
        ]:
            self.assertEqual(extract(text, category="apparel").amount, amount, text)

    def test_a_rate_in_the_next_clause_prices_the_only_item(self):
        self.assertEqual(extract("2 kurti bhej do, 500 each", category="apparel").amount, 1000.0)
        # ...but never jumps over another item to reach this one.
        r = extract("2 kurti 500 each, 3 saree 800 each", category="apparel")
        self.assertEqual(r.amount, 3400.0)


class TestRateQuotedPerKilo(unittest.TestCase):
    """A real group chat: the rate quoted per kilo, the order in kilos, "200rs" glued."""

    def test_spring_onions_two_kilo_at_200(self):
        chat = [
            at(0, "hello bhaiya"), at(1, "spring onions hai kya?"), at(1, "ha", shop=True),
            at(1, "1kg ka 200rs", shop=True), at(2, "meko 2kg chaiye"), at(2, "Ok", shop=True),
        ]
        for category in ("kirana", "general"):
            orders = resolve_all(chat, "Isha Agarwal", category)
            self.assertEqual(len(orders), 1, category)
            o = orders[0]
            self.assertEqual((o.direction, o.amount), ("sale", 400.0), category)
            line = o.items[0]
            self.assertEqual(
                (line.modifier, line.name, line.quantity, line.unit, line.unit_price),
                ("spring", "onion", 2.0, "kg", 200.0), category,
            )

    def test_currency_written_onto_the_digits(self):
        for text in ["200rs", "200rupaye", "₹200", "rs200", "200 rs", "200/-"]:
            self.assertEqual(extract(text, category="kirana").amount, 200.0, text)


class TestTypedLabels(unittest.TestCase):
    """What the edit sheet and the dashboard store when an item is typed."""

    def test_a_leading_count_becomes_the_quantity(self):
        for typed, label in [
            ("do kurti", "kurti 2"), ("Do Kurti", "Kurti 2"), ("2 kurti", "kurti 2"),
            ("teen blue saree", "blue saree 3"), ("2 kg chawal", "chawal 2kg"),
            ("दो कुर्ती", "कुर्ती 2"),
        ]:
            self.assertEqual(normalise_label(typed), label, typed)

    def test_everything_else_is_left_alone(self):
        for typed in ["kurti 2", "chawal 2kg", "kurti", "5 star", "dhai sau", "do"]:
            self.assertEqual(normalise_label(typed), typed, typed)


class TestLatePayments(unittest.TestCase):
    ORDER = [at(0, "2 kurti chahiye"), at(1, "2000 hua total", shop=True), at(2, "udhaar likh lo")]

    def _after(self, minutes, body, **kw):
        return resolve_all(self.ORDER + [at(minutes, body)], "Rahul Verma", "apparel", **kw)

    def test_the_order_alone_is_credit(self):
        orders = resolve_all(self.ORDER, "Rahul Verma", "apparel")
        self.assertEqual([(o.direction, o.amount, o.mode) for o in orders], [("sale", 2000.0, "udhaar")])

    def test_a_late_payment_pays_the_order(self):
        for wording, mode in [
            ("Payment done.", "cash"), ("UPI kar diya.", "upi"), ("Cash de diya.", "cash"),
            ("Paise bhej diye.", "upi"), ("पैसे भेज दिए", "upi"), ("maine payment kar di", "cash"),
            ("2000 bhej diye", "upi"),
        ]:
            orders = self._after(50, wording)
            self.assertEqual(len(orders), 1, wording)
            self.assertEqual((orders[0].direction, orders[0].amount, orders[0].mode), ("sale", 2000.0, mode), wording)
            self.assertIn("paid-later", orders[0].signals, wording)

    def test_after_a_pay_link_reminder_it_was_paid_by_upi(self):
        orders = self._after(50, "Payment done.", pay_link_sent=[T0 + timedelta(minutes=30)])
        self.assertEqual(orders[0].mode, "upi")

    def test_a_part_payment_stays_separate(self):
        orders = self._after(50, "500 bhej diye")
        self.assertEqual([(o.direction, o.amount) for o in orders], [("sale", 2000.0), ("payment", 500.0)])
        self.assertEqual(orders[0].mode, "udhaar")

    def test_a_payment_weeks_later_is_not_folded(self):
        orders = self._after(60 * 24 * 10, "Payment done.")
        self.assertEqual([o.direction for o in orders], ["sale", "payment"])

    def test_an_order_paid_on_the_spot_is_not_paid_twice(self):
        orders = resolve_all(
            [at(0, "2 kurti chahiye"), at(1, "2000 hua total", shop=True), at(2, "gpay kar diya"),
             at(60, "payment done")],
            "Rahul Verma", "apparel",
        )
        self.assertEqual([(o.direction, o.mode) for o in orders], [("sale", "upi"), ("payment", "unknown")])

    def test_through_an_export(self):
        export = (
            "30/10/26, 10:00 am - Rahul Verma: 2 kurti chahiye\n"
            "30/10/26, 10:01 am - Gupta Store: 2000 hua total\n"
            "30/10/26, 10:02 am - Rahul Verma: udhaar likh lo\n"
            "30/10/26, 10:30 am - Gupta Store: Namaste Rahul Verma 🙏 This is Gupta Store. Your balance of ₹2,000 "
            "is pending as of 30 Oct. You can pay using the link below. Thank you!\n"
            "upi://pay?pa=gupta@upi&pn=Gupta&am=2000.00&cu=INR\n"
            "30/10/26, 10:52 am - Rahul Verma: Payment done.\n"
        )
        result = ingest_conversation(
            export, ChatCursor(chat_id="c1"), customer="Rahul Verma", category="apparel",
            shop_aliases={"Gupta Store"},
        )
        self.assertEqual([(o.direction, o.amount, o.mode) for o in result.orders], [("sale", 2000.0, "upi")])


if __name__ == "__main__":
    unittest.main()


class TestEndpoints(unittest.TestCase):
    def setUp(self) -> None:
        from fastapi.testclient import TestClient

        from app import main
        from app.store import LedgerStore

        self.main = main
        main.STORE = LedgerStore(":memory:")
        self.client = TestClient(main.app)

    def test_a_typed_count_is_stored_as_a_quantity(self):
        self.main.STORE.create_shop("s1", "Gupta Store")
        self.client.post("/shops/s1/transactions", json={
            "customer_name": "Shyam", "amount": 2000, "items": ["do kurti"],
        })
        found = self.client.get("/shops/s1/transactions").json()["transactions"]
        self.assertEqual(found[0]["items"], "kurti 2")
        top = self.client.get("/shops/s1/items").json()["items"]
        self.assertEqual((top[0]["name"], top[0]["quantity"]), ("kurti", 2.0))

    def test_the_screen_lane_folds_a_late_payment(self):
        def msg(minute, body, out=False):
            return {"body": body, "is_outgoing": out,
                    "timestamp": (T0 + timedelta(minutes=minute)).isoformat()}

        reply = self.client.post("/ingest/screen", json={
            "chat_id": "c1", "customer_name": "Rahul Verma", "category": "apparel",
            "messages": [
                msg(0, "2 kurti chahiye"), msg(1, "2000 hua total", out=True), msg(2, "udhaar likh lo"),
                msg(30, "Namaste Rahul Verma 🙏 This is Gupta Store. Your balance of ₹2,000 is pending as of "
                        "30 Oct. You can pay using the link below. Thank you!\n\nupi://pay?pa=g@upi&am=2000.00",
                    out=True),
                msg(52, "Payment done."),
            ],
        }).json()
        self.assertEqual(
            [(o["direction"], o["amount"], o["mode"]) for o in reply["orders"]],
            [("sale", 2000.0, "upi")],
        )
