"""Chats read off a real phone (Gupta Store test group, 29-31 Oct), and the phrasings around them."""

import unittest
from datetime import datetime, timedelta

from app.extraction.conversation import Turn, resolve_all
from app.extraction.pipeline import extract

T0 = datetime(2026, 10, 29, 23, 26)


def chat(*rows, customer="Isha"):
    return [
        Turn(sender="You" if shop else customer, body=body, timestamp=T0 + timedelta(minutes=m),
             is_shopkeeper=shop, content_hash=f"h{i}")
        for i, (m, body, shop) in enumerate(rows)
    ]


class TestGroupChatScreenshots(unittest.TestCase):
    def test_a_group_member_quoting_per_kilo_is_a_rate(self):
        """Hrishikesh (not the phone's owner) wrote "1kg ka 200rs"; it was booked as the Rs 200 bill."""
        orders = resolve_all(chat(
            (0, "hello bhaiya", False), (0, "2kg spring onions chaiye", False), (1, "kitna hua?", False),
            (3, "1kg ka 200rs", False), (4, "okay i need 2kg", False),
            (6, "hello bhaiya", False), (7, "spring onions hai kya?", False), (7, "ha", False),
            (7, "1kg ka 200rs", False), (8, "meko 2kg chaiye", False), (8, "Ok", False),
        ), "Isha", "kirana")
        self.assertEqual(len(orders), 1)
        o = orders[0]
        self.assertEqual(o.amount, 400.0)
        line = o.items[0]
        self.assertEqual((line.modifier, line.name, line.quantity, line.unit, line.unit_price),
                         ("spring", "onion", 2.0, "kg", 200.0))

    def test_one_for_a_thousand_then_two(self):
        """'1 for 1000rs' then 'i need 2 dupattas' showed NO PRICE YET; it is Rs 2,000."""
        orders = resolve_all(chat(
            (0, "hello bhaiya", False), (0, "yellow dupatta chaiye", False), (0, "kitna hua", False),
            (1, "1 for 1000rs", True), (1, "i need 2 dupattas", False), (1, "okay", True),
        ), "Isha", "apparel")
        self.assertEqual(len(orders), 1)
        o = orders[0]
        self.assertEqual((o.amount, o.items[0].modifier, o.items[0].name, o.items[0].quantity),
                         (2000.0, "yellow", "dupatta", 2.0))


class TestRateForOne(unittest.TestCase):
    def test_price_for_one_in_a_message(self):
        for text, cat, amount, qty in [
            ("1 for 2000 kurta", "apparel", 2000.0, None),
            ("2 kurta 1 ka 2000", "apparel", 4000.0, 2.0),
            ("kurta 1 for 2000, 2 chahiye", "apparel", 4000.0, 2.0),
            ("kurta 2000 ka ek, do chahiye", "apparel", 4000.0, 2.0),
            ("dupatta 1000 each, 3 chaiye", "apparel", 3000.0, 3.0),
            ("kurti 500 each", "apparel", 500.0, None),
        ]:
            r = extract(text, category=cat).to_dict()
            self.assertEqual(r["amount"], amount, text)
            self.assertEqual(r["line_items"][0]["quantity"], qty, text)

    def test_a_unit_after_ki_ek_is_not_a_rate(self):
        r = extract("crocin 650 ki 1 strip aur 1 vicks | 85".replace("|", ","), category="pharmacy").to_dict()
        self.assertEqual(r["amount"], 85.0)


class TestMeasureAnywhere(unittest.TestCase):
    def test_a_lone_items_measure_is_found(self):
        for text in ["spring onion chahiye, 2 kg", "spring onion bhej do 2kg", "spring onion - 2 kg",
                     "spring onion\n2kg", "spring onion do kilo", "2kg chahiye spring onion"]:
            line = extract(text, category="kirana").to_dict()["line_items"][0]
            self.assertEqual((line["quantity"], line["unit"]), (2.0, "kg"), text)
        for text, qty, unit in [("spring onion 500g", 500.0, "g"), ("spring onion x2", 2.0, "pc")]:
            line = extract(text, category="kirana").to_dict()["line_items"][0]
            self.assertEqual((line["quantity"], line["unit"]), (qty, unit), text)

    def test_two_measures_are_not_guessed(self):
        line = extract("chawal 2kg aur 3kg", category="kirana").to_dict()["line_items"][0]
        self.assertEqual(line["quantity"], 2.0)   # beside the item, as before


if __name__ == "__main__":
    unittest.main()
