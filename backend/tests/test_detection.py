"""
Order detection in real chat: the failures `eval/chats.py` found, pinned.

Each class is one way the engine used to read a WhatsApp thread wrongly,
with the message that showed it. The benchmark measures how often these
happen; these tests stop any one of them coming back.
"""

import unittest
from datetime import datetime, timedelta

from app.extraction.conversation import Turn, is_commitment, is_implicit_order, resolve_all
from app.extraction.numbers import find_numbers
from app.extraction.rules import Direction, apply_rules, extract_items

T0 = datetime(2026, 10, 27, 9, 0)


def orders(*rows, category="kirana"):
    turns = [
        Turn(
            sender="Shop" if shop else "Rahul Verma", body=body,
            timestamp=T0 + timedelta(minutes=i), is_shopkeeper=shop, content_hash=f"h{i}",
        )
        for i, (body, shop) in enumerate(rows)
    ]
    return resolve_all(turns, "Rahul Verma", category)


def names(order):
    return {i.display_name for i in order.items}


class TestMoney(unittest.TestCase):
    def test_digit_group_commas_are_part_of_the_number(self):
        """"total 1,250 hua" was read as Rs 250."""
        self.assertEqual(find_numbers("total 1,250 hua")[0][0], 1250)
        self.assertEqual(find_numbers("1,25,000 ho gaya")[0][0], 125000)
        self.assertEqual([v for v, _ in find_numbers("2,3 kurti")], [2, 3])

    def test_a_count_is_never_money(self):
        """"2 packet parle g, 1 kurkure" booked Rs 1."""
        self.assertIsNone(apply_rules("2 packet parle g, 1 kurkure", "kirana").amount)
        self.assertIsNone(apply_rules("do kele aur ek kilo seb", "kirana").amount)

    def test_a_total_after_an_order_is_the_bill_not_a_rate(self):
        """Read as a rate, Rs 60 for two strips became Rs 4,22,500."""
        o = orders(("dolo 650 ki 2 strip bhej dijiye", False), ("60 rupaye", True),
                   category="pharmacy")
        self.assertEqual(o[0].amount, 60)
        o = orders(("bhaiya 2 kilo chawal aur 1 packet chai bhej do", False),
                   ("theek hai, 250 hua", True))
        self.assertEqual(o[0].amount, 250)

    def test_a_price_before_the_order_is_still_a_rate(self):
        o = orders(("sarson tel 1 litre kitne ka hai?", False), ("180", True),
                   ("ok 2 bhej do", False))
        self.assertEqual(o[0].amount, 360)

    def test_a_per_unit_price_said_the_short_way(self):
        """"400 kilo" is Rs 400 a kilo, not four hundred kilos."""
        o = orders(("paneer hai kya aaj?", False), ("haan hai, 400 kilo", True),
                   ("theek hai aadha kilo de dena", False))
        self.assertEqual(o[0].amount, 200)

    def test_the_customers_own_figure_is_kept(self):
        o = orders(("5 kg aata aur 1 litre tel, 480 rupaye cash de dunga", False))
        self.assertEqual(o[0].amount, 480)
        self.assertEqual(o[0].mode, "cash")


class TestWhatIsAnOrder(unittest.TestCase):
    def test_a_list_with_quantities_is_an_order(self):
        """Most orders arrive with no ordering verb at all."""
        o = orders(("2 packet parle g, 1 kurkure", False))
        self.assertEqual(len(o), 1)
        self.assertEqual({i.name for i in o[0].items}, {"parle", "kurkure"})

    def test_questions_complaints_and_cancellations_are_not(self):
        for text in [
            "kal 3 kg aata bheja tha wo kharab tha",
            "kal 2 kg dal mangwayi thi wo abhi tak nahi aayi",
            "2 kg chawal kitne ka hai?",
        ]:
            rules = apply_rules(text, "kirana")
            self.assertFalse(is_implicit_order(text, rules), text)
            self.assertEqual(orders((text, False)), [], text)
        self.assertFalse(is_commitment("bhaiya order cancel kar do, aaj nahi chahiye"))

    def test_a_complaint_is_not_a_payment(self):
        """"bheja tha" about goods invented a payment."""
        self.assertIsNot(
            apply_rules("kal 3 kg aata bheja tha wo kharab tha", "kirana").direction,
            Direction.PAYMENT,
        )

    def test_please_send_is_an_order(self):
        self.assertTrue(is_commitment("please send 2 dettol soap and 1 vim bar"))

    def test_items_added_later_join_the_order(self):
        o = orders(("2 kg chawal bhej do", False), ("ok", True),
                   ("aur haan 1 packet namak bhi", False))
        self.assertEqual({i.name for i in o[0].items}, {"chawal", "namak"})

    def test_an_amount_written_down_with_no_item_is_still_an_entry(self):
        o = orders(("Rs.250/- ka saman likh lo", False))
        self.assertEqual((o[0].amount, o[0].mode, o[0].items), (250, "udhaar", []))


class TestPaidOnTheSpot(unittest.TestCase):
    def test_goods_and_payment_together_are_a_sale(self):
        """"3 sabun 120 ka UPI kar diya" was booked as a debt being settled."""
        o = orders(("3 sabun 120 ka UPI kar diya", False))
        self.assertEqual(o[0].direction, "sale")
        self.assertEqual((o[0].amount, o[0].mode), (120, "upi"))

    def test_paying_right_after_an_order_pays_that_order(self):
        """The sale used to vanish, leaving only a payment of nothing."""
        o = orders(("bhaiya 2 kg pyaaz 1 kg tamatar aur dhaniya bhej do", False),
                   ("130 hua total", True), ("paytm kar diya", False))
        self.assertEqual(len(o), 1)
        self.assertEqual((o[0].direction, o[0].amount, o[0].mode), ("sale", 130, "upi"))

    def test_settling_an_old_debt_is_still_a_payment(self):
        o = orders(("kal ke 2 dabbe dahi ke paise bhej diye 80", False))
        self.assertEqual((o[0].direction, o[0].amount), ("payment", 80))

    def test_a_payment_naming_no_instrument_does_not_guess_one(self):
        o = orders(("kal ke 2 dabbe dahi ke paise bhej diye 80", False))
        self.assertEqual(o[0].mode, "unknown")


class TestVocabulary(unittest.TestCase):
    def test_everyday_grocery_is_known(self):
        found = extract_items("1 kg besan, 500 gram sooji, aadha kilo jeera, paav kilo adrak", "kirana")
        self.assertEqual({i.name for i in found}, {"besan", "sooji", "jeera", "adrak"})

    def test_hindi_plurals_resolve_to_the_singular(self):
        self.assertEqual(extract_items("ek darjan ande", "kirana")[0].name, "anda")
        self.assertEqual(extract_items("2 kabze", "hardware")[0].name, "kabza")

    def test_varieties_stay_separate_lines(self):
        """"toor dal" and "moong dal" were both "dal", then merged into one."""
        o = orders(("toor dal 2 kg moong dal 1 kg", False))
        self.assertEqual(names(o[0]), {"toor dal", "moong dal"})

    def test_a_variety_word_alone_is_the_item(self):
        self.assertEqual(extract_items("2 kg chana", "kirana")[0].name, "chana")
        self.assertEqual(extract_items("1 kg chana dal", "kirana")[0].display_name, "chana dal")

    def test_a_measure_does_not_cross_aur(self):
        found = extract_items("2 coke 2 litre wali aur 1 lays", "kirana")
        lays = next(i for i in found if i.name == "lays")
        self.assertEqual((lays.quantity, lays.unit), (1, None))

    def test_a_medicines_strength_is_part_of_its_name(self):
        found = extract_items("dolo 650 ki 2 strip", "pharmacy")
        self.assertEqual((found[0].name, found[0].quantity, found[0].unit), ("dolo 650", 2, "strip"))


if __name__ == "__main__":
    unittest.main()
