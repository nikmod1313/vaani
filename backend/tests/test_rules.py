"""Tests for the deterministic extraction layer."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.extraction.rules import (  # noqa: E402
    Direction,
    PaymentMode,
    apply_rules,
    detect_direction,
    detect_mode,
    extract_items,
)


class TestPaymentMode(unittest.TestCase):
    def test_cash(self):
        for text in ["150 nakad", "cash de diya", "nagad payment"]:
            self.assertEqual(detect_mode(text)[0], PaymentMode.CASH, text)

    def test_upi(self):
        for text in ["UPI se bheja", "phonepe kar diya", "gpay 200", "online transfer"]:
            self.assertEqual(detect_mode(text)[0], PaymentMode.UPI, text)

    def test_udhaar_explicit(self):
        for text in ["dhai sau ka udhaar", "baaki likh lena", "credit kar do"]:
            self.assertEqual(detect_mode(text)[0], PaymentMode.UDHAAR, text)

    def test_udhaar_implied_by_phrase(self):
        # No "udhaar" anywhere -- the phrase alone means credit.
        self.assertEqual(detect_mode("chawal bhej do, likh lena")[0], PaymentMode.UDHAAR)
        self.assertEqual(detect_mode("khaate mein daal do")[0], PaymentMode.UDHAAR)

    def test_unknown(self):
        self.assertEqual(detect_mode("2 kilo chawal bhej do")[0], PaymentMode.UNKNOWN)


class TestDirection(unittest.TestCase):
    def test_payment_received(self):
        for text in ["payment mila 500", "paise bhej diye", "jama kar diya", "500 de diye"]:
            self.assertEqual(detect_direction(text), Direction.PAYMENT, text)

    def test_sale(self):
        self.assertEqual(detect_direction("2 kilo chawal bhej do"), Direction.SALE)


class TestItems(unittest.TestCase):
    def test_quantity_before_item(self):
        items = extract_items("2 kilo chawal")
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0].name, "chawal")
        self.assertEqual(items[0].quantity, 2)
        self.assertEqual(items[0].unit, "kg")

    def test_multiple_items(self):
        items = extract_items("2 kilo chawal aur 1 packet chai")
        self.assertEqual([i.name for i in items], ["chawal", "chai"])
        self.assertEqual(items[1].quantity, 1)
        self.assertEqual(items[1].unit, "packet")

    def test_devanagari_items(self):
        items = extract_items("2 किलो चावल")
        self.assertEqual(items[0].name, "चावल")

    def test_item_without_quantity(self):
        items = extract_items("namak bhej do")
        self.assertEqual(items[0].name, "namak")
        self.assertIsNone(items[0].quantity)

    def test_fractional_quantity(self):
        items = extract_items("adha kilo cheeni")
        self.assertEqual(items[0].quantity, 0.5)

    def test_label(self):
        items = extract_items("2 kilo chawal")
        self.assertEqual(items[0].label(), "chawal 2kg")


class TestAmountSelection(unittest.TestCase):
    """Picking which number is the money is the subtlest job here."""

    def test_currency_marker_wins(self):
        r = apply_rules("2 kilo chawal, 250 rupaye")
        self.assertEqual(r.amount, 250)
        self.assertEqual(r.amount_source, "currency-adjacent")

    def test_rupee_symbol(self):
        r = apply_rules("chawal ₹250")
        self.assertEqual(r.amount, 250)

    def test_mode_adjacent(self):
        r = apply_rules("2 kilo chawal aur 1 packet chai, dhai sau ka udhaar")
        self.assertEqual(r.amount, 250)
        self.assertEqual(r.amount_source, "mode-adjacent")

    def test_quantities_are_not_mistaken_for_amount(self):
        r = apply_rules("2 kilo chawal aur 1 packet chai, dhai sau ka udhaar")
        self.assertNotIn(r.amount, (1, 2))

    def test_largest_unused_is_a_guess_not_a_resolution(self):
        r = apply_rules("2 kilo chawal 300")
        self.assertEqual(r.amount, 300)
        # A guess must not claim certainty -- the model layer confirms it.
        self.assertNotIn("amount", r.resolved)

    def test_no_number_no_amount(self):
        r = apply_rules("chawal bhej do")
        self.assertIsNone(r.amount)


class TestVerbTraps(unittest.TestCase):
    """
    Hindi words that are both goods and verbs. Same class as "do" in the
    number parser, and each one silently invented ledger entries.
    """

    def test_diya_is_not_an_oil_lamp(self):
        # "phonepe kar diya" was booking a purchase of diyas on every payment.
        r = apply_rules("200 rupaye phonepe kar diya")
        self.assertEqual(r.items, [])
        self.assertEqual(r.direction, Direction.PAYMENT)

    def test_daal_do_is_not_lentils(self):
        r = apply_rules("khaate mein daal do 250")
        self.assertEqual([i.name for i in r.items], [])

    def test_dal_with_a_quantity_is_lentils(self):
        r = apply_rules("dal 2 kilo bhej do")
        self.assertEqual([i.name for i in r.items], ["dal"])
        self.assertEqual(r.items[0].quantity, 2)


class TestQuantityIsNotMoney(unittest.TestCase):
    def test_quantity_alone_yields_no_amount(self):
        """
        "2 kilo chawal bhej do" once booked Rs 2 -- the quantity was reused as
        the price. A missing amount is recoverable; a wrong one is not.
        """
        r = apply_rules("2 kilo chawal bhej do")
        self.assertIsNone(r.amount)
        self.assertEqual(r.items[0].quantity, 2)

    def test_trailing_quantity_attaches_to_its_own_item(self):
        # The trailing "2 kilo" once drifted onto atta, orphaning a number
        # that then got picked as the amount.
        r = apply_rules("dal 2 kilo aur atta 5 kilo")
        self.assertIsNone(r.amount)
        self.assertEqual([(i.name, i.quantity) for i in r.items],
                         [("dal", 2), ("atta", 5)])

    def test_bare_trailing_number_is_money_not_quantity(self):
        # No unit -> it is a price, not three hundred kilos of rice.
        r = apply_rules("chawal 300")
        self.assertEqual(r.amount, 300)


class TestDevanagariBoundaries(unittest.TestCase):
    """
    Regression: Python excludes Devanagari matras from \\w, so a trailing
    `\\b` never matches after them. Every Devanagari phrase pattern was dead
    code until whitespace boundaries replaced them.
    """

    def test_payment_detected_in_devanagari(self):
        self.assertEqual(detect_direction("डेढ़ सौ रुपये नकद मिले"), Direction.PAYMENT)
        self.assertEqual(detect_direction("५०० रुपये यूपीआई से भेजे"), Direction.PAYMENT)

    def test_credit_phrase_detected_in_devanagari(self):
        self.assertEqual(detect_mode("खाते में डाल दो")[0], PaymentMode.UDHAAR)

    def test_word_ending_in_matra_is_matched(self):
        # "मिले" ends in a matra; a \b-anchored pattern silently missed it.
        self.assertEqual(detect_direction("पैसे मिले"), Direction.PAYMENT)


class TestFullRules(unittest.TestCase):
    def test_the_canonical_order(self):
        text = "Bhaiya 2 kilo chawal aur 1 packet chai bhej do, dhai sau ka udhaar likh lena"
        r = apply_rules(text)

        self.assertEqual(r.amount, 250)
        self.assertEqual(r.mode, PaymentMode.UDHAAR)
        self.assertEqual(r.direction, Direction.SALE)
        self.assertEqual([i.name for i in r.items], ["chawal", "chai"])
        self.assertEqual(r.items[0].quantity, 2)
        self.assertEqual(r.items[1].quantity, 1)
        self.assertTrue(r.is_complete())

    def test_cash_sale(self):
        r = apply_rules("1 litre tel aur namak, 120 rupaye cash")
        self.assertEqual(r.amount, 120)
        self.assertEqual(r.mode, PaymentMode.CASH)
        self.assertEqual([i.name for i in r.items], ["tel", "namak"])

    def test_payment_back(self):
        r = apply_rules("Rahul ne 500 rupaye UPI se bhej diye")
        self.assertEqual(r.amount, 500)
        self.assertEqual(r.mode, PaymentMode.UPI)
        self.assertEqual(r.direction, Direction.PAYMENT)

    def test_incomplete_is_flagged(self):
        r = apply_rules("chawal bhej do")
        self.assertFalse(r.is_complete())


if __name__ == "__main__":
    unittest.main(verbosity=2)
