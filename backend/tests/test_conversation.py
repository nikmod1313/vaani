"""
Conversation threading and category lexicons.

The cases here are the ones that segmentation alone gets wrong: an order
whose item and quantity were typed by different people, several turns apart.
"""

import unittest
from datetime import datetime, timedelta

from app.extraction.categories import (
    Category,
    all_known_items,
    all_known_modifiers,
    items_for,
    modifiers_for,
    units_for,
)
from app.extraction.conversation import (
    Turn,
    build_threads,
    is_commitment,
    is_enquiry,
    resolve,
    resolve_all,
)
from app.extraction.rules import apply_rules
from app.extraction.whatsapp import ChatCursor, ingest_conversation, is_shopkeeper, parse_export

T0 = datetime(2026, 8, 26, 9, 41)


def turns(*rows, start=T0, step=1):
    """(body, is_shopkeeper) pairs -> Turns one minute apart."""
    out = []
    for i, (body, shop) in enumerate(rows):
        out.append(
            Turn(
                sender="Sharma Kirana" if shop else "Rahul Verma",
                body=body,
                timestamp=start + timedelta(minutes=i * step),
                is_shopkeeper=shop,
                content_hash=f"h{i}",
            )
        )
    return out


class TestCommitment(unittest.TestCase):
    def test_hindi_commitments(self):
        for text in [
            "mujhe 3 kharidne hai",
            "2 chahiye",
            "bhej do",
            "de dena",
            "3 le lunga",
            "order karna hai",
        ]:
            self.assertTrue(is_commitment(text), text)

    def test_english_commitments(self):
        for text in ["i want 3 pieces", "send me 2", "i need one", "i'll take it"]:
            self.assertTrue(is_commitment(text), text)

    def test_enquiries_are_not_commitments(self):
        for text in [
            "is there an availability of blue kurtis",
            "blue kurti hai kya",
            "kitne ka hai",
            "how much for a saree",
            "do you have red dupatta",
        ]:
            self.assertFalse(is_commitment(text), text)
            self.assertTrue(is_enquiry(text), text)


class TestCarriedTopic(unittest.TestCase):
    """The reason this module exists."""

    def test_item_carries_across_the_shopkeepers_reply(self):
        order = resolve(
            build_threads(
                turns(
                    ("is there an availability of blue kurtis", False),
                    ("yes it is available, 500 ka", True),
                    ("mujhe 3 kharidne hai", False),
                ),
                "Rahul Verma",
            )[0],
            "apparel",
        )
        self.assertIsNotNone(order)
        self.assertTrue(order.carried_topic)
        self.assertEqual(order.items[0].name, "kurti")
        self.assertEqual(order.items[0].modifier, "blue")
        self.assertEqual(order.items[0].quantity, 3)
        self.assertEqual(order.items[0].unit_price, 500.0)
        self.assertEqual(order.amount, 1500.0)

    def test_english_quantity_with_unit(self):
        order = resolve(
            build_threads(
                turns(
                    ("do you have red saree", False),
                    ("haan hai", True),
                    ("i want 3 pieces", False),
                ),
                "Rahul Verma",
            )[0],
            "apparel",
        )
        self.assertIsNotNone(order)
        self.assertEqual(order.items[0].name, "saree")
        self.assertEqual(order.items[0].quantity, 3)
        self.assertEqual(order.items[0].unit, "pc")

    def test_item_named_in_the_commitment_wins_over_the_topic(self):
        order = resolve(
            build_threads(
                turns(
                    ("blue kurti hai kya", False),
                    ("haan", True),
                    ("nahi, 2 saree chahiye", False),
                ),
                "Rahul Verma",
            )[0],
            "apparel",
        )
        self.assertEqual(order.items[0].name, "saree")
        self.assertFalse(order.carried_topic)


class TestNothingIsInvented(unittest.TestCase):
    def test_an_enquiry_alone_books_nothing(self):
        threads = build_threads(
            turns(("is blue kurti available", False), ("yes, 500 ka", True)),
            "Rahul Verma",
        )
        self.assertIsNone(resolve(threads[0], "apparel"))

    def test_the_shopkeeper_cannot_place_an_order(self):
        """"bhej do" from the shop is a nudge to a supplier, not a sale."""
        threads = build_threads(
            turns(("blue kurti 500 ka hai", True), ("bhej do", True)),
            "Rahul Verma",
        )
        self.assertIsNone(resolve(threads[0], "apparel"))

    def test_a_commitment_naming_nothing_books_nothing(self):
        threads = build_threads(turns(("3 chahiye", False)), "Rahul Verma")
        self.assertIsNone(resolve(threads[0], "apparel"))

    def test_no_rate_means_no_amount_rather_than_a_guess(self):
        order = resolve(
            build_threads(
                turns(("red saree hai kya", False), ("haan", True), ("2 chahiye", False)),
                "Rahul Verma",
            )[0],
            "apparel",
        )
        self.assertEqual(order.items[0].quantity, 2)
        self.assertIsNone(order.amount)


class TestThreadBoundaries(unittest.TestCase):
    def test_silence_splits_threads(self):
        rows = turns(
            ("blue kurti hai kya", False),
            ("haan", True),
        ) + turns(
            ("3 chahiye", False),
            start=T0 + timedelta(hours=6),
        )
        self.assertEqual(len(build_threads(rows, "Rahul Verma")), 2)

    def test_a_stale_topic_does_not_reach_a_later_order(self):
        """Monday's kurti must not attach to Friday's bare "3 chahiye"."""
        rows = turns(("blue kurti hai kya", False)) + turns(
            ("3 chahiye", False), start=T0 + timedelta(days=4)
        )
        self.assertEqual(resolve_all(rows, "Rahul Verma", "apparel"), [])

    def test_capture_order_does_not_decide_chronology(self):
        """A scroll-harvest reads upward; the resolver must still see time order."""
        rows = turns(
            ("is there an availability of blue kurtis", False),
            ("yes, 500 ka", True),
            ("mujhe 3 kharidne hai", False),
        )
        orders = resolve_all(list(reversed(rows)), "Rahul Verma", "apparel")
        self.assertEqual(len(orders), 1)
        self.assertEqual(orders[0].amount, 1500.0)


class TestCategories(unittest.TestCase):
    def test_apparel_knows_clothes_and_kirana_does_not(self):
        self.assertIn("kurti", items_for("apparel"))
        self.assertNotIn("kurti", items_for("kirana"))
        self.assertIn("chawal", items_for("kirana"))

    def test_general_is_open_vocabulary_not_an_empty_one(self):
        """An unset category must widen the lexicon, never erase it."""
        self.assertEqual(items_for("general"), all_known_items())
        self.assertIn("chawal", items_for(None))
        self.assertIn("kurti", items_for(None))

    def test_general_keeps_modifiers_too(self):
        """
        The regression that put "kurti 3" in a real khata instead of
        "blue kurti 3", because the shop had not picked a category yet.
        """
        self.assertEqual(modifiers_for("general"), all_known_modifiers())
        self.assertIn("blue", modifiers_for(None))
        self.assertEqual(
            apply_rules("3 blue kurti 500 each", "general").items[0].label(),
            "blue kurti 3",
        )

    def test_unknown_category_degrades_rather_than_raising(self):
        self.assertIs(Category.parse("bakery"), Category.GENERAL)
        self.assertIs(Category.parse(None), Category.GENERAL)

    def test_category_units(self):
        self.assertIn("meter", units_for("apparel"))
        self.assertIn("strip", units_for("pharmacy"))

    def test_the_lexicon_actually_changes_extraction(self):
        self.assertEqual(apply_rules("3 kurti chahiye", "kirana").items, [])
        self.assertEqual(len(apply_rules("3 kurti chahiye", "apparel").items), 1)


class TestPlurals(unittest.TestCase):
    def test_english_plural_resolves_to_the_singular(self):
        items = apply_rules("2 kurtis chahiye", "apparel").items
        self.assertEqual(items[0].name, "kurti")

    def test_es_plural(self):
        self.assertEqual(apply_rules("3 dabbas", "general").items[0].name, "dabba")

    def test_a_lexicon_plural_is_not_stripped(self):
        """"jeans" is already the word; "jean" is not."""
        self.assertEqual(apply_rules("2 jeans chahiye", "apparel").items[0].name, "jeans")


class TestStatedRates(unittest.TestCase):
    def test_rate_times_quantity_is_the_amount(self):
        result = apply_rules("3 kurti 500 each", "apparel")
        self.assertEqual(result.amount, 1500.0)
        self.assertEqual(result.amount_source, "line-totals")

    def test_a_mode_word_after_a_possessive_still_means_the_total(self):
        """The regression the split rate-marker lists exist for."""
        result = apply_rules("2 kilo chawal, dhai sau ka udhaar", "kirana")
        self.assertEqual(result.amount, 250.0)
        self.assertIsNone(result.items[0].unit_price)

    def test_each_is_unambiguous_even_before_a_mode_word(self):
        self.assertEqual(apply_rules("3 kurti 500 each udhaar", "apparel").amount, 1500.0)

    def test_a_rate_is_never_inferred_by_division(self):
        """A total with a quantity must not become a per-unit price."""
        self.assertIsNone(apply_rules("3 kurti 1500 ka udhaar", "apparel").items[0].unit_price)


class TestMeasureSurvivesAPrice(unittest.TestCase):
    """
    From a real chat, sent one word per message:

        "2kg for"  "500 only"  "organic"  "grown"  "tamatar"

    Joined for extraction, the 500 overwrote the 2 as the pending quantity
    while the unit "kg" from the measure survived beside it -- so the entry
    booked FIVE HUNDRED KILOS of tomatoes for no money at all.
    """

    def test_a_bare_number_does_not_overwrite_a_measure(self):
        result = apply_rules("2kg for 500 only organic grown tamatar", "kirana")
        self.assertEqual(result.items[0].quantity, 2)
        self.assertEqual(result.items[0].unit, "kg")

    def test_the_bare_number_becomes_the_amount(self):
        self.assertEqual(
            apply_rules("2kg for 500 only organic grown tamatar", "kirana").amount,
            500.0,
        )

    def test_a_bare_number_carries_no_borrowed_unit(self):
        """Without clearing the unit, "500" inherited the "kg" beside it."""
        result = apply_rules("5 kg 200 rupaye aloo", "kirana")
        self.assertEqual(result.items[0].quantity, 5)
        self.assertEqual(result.items[0].unit, "kg")
        self.assertEqual(result.amount, 200.0)

    def test_a_second_measure_still_replaces_the_first(self):
        """Only BARE numbers are refused; a real measure must still win."""
        items = apply_rules("2 kilo chawal aur 5 kilo atta", "kirana").items
        self.assertEqual((items[0].name, items[0].quantity), ("chawal", 2))
        self.assertEqual((items[1].name, items[1].quantity), ("atta", 5))


class TestQuotedRateVersusTotal(unittest.TestCase):
    """
    Whether a quoted price is per-unit or for the whole bundle decides
    whether the bill gets multiplied. Getting it backwards is the difference
    between Rs 500 and Rs 1000 of someone's money.

    The separator is whether the shopkeeper's own run of messages mentioned a
    quantity: a bare price answers "what does one cost", a price beside a
    measure prices that bundle.
    """

    def _order(self, rows, category="kirana"):
        turns = []
        for i, (body, shop) in enumerate(rows):
            turns.append(
                Turn(
                    sender="Sharma Kirana" if shop else "Rachit M",
                    body=body,
                    timestamp=T0 + timedelta(minutes=i),
                    is_shopkeeper=shop,
                    content_hash=f"q{i}",
                )
            )
        orders = resolve_all(turns, "Rachit M", category)
        return orders[0] if orders else None

    def test_a_price_beside_a_measure_is_a_total(self):
        """From a real chat: "2kg for" / "500 only" is 500, not 2 x 500."""
        order = self._order([
            ("2kg tamatar chaiye", False),
            ("2kg for", True), ("500 only", True), ("organic grown tamatar", True),
            ("haan bhej do", False),
        ])
        self.assertEqual(order.amount, 500.0)
        self.assertIsNone(order.items[0].unit_price)

    def test_a_price_against_one_unit_is_a_rate_not_a_bundle(self):
        """
        From a real chat, and a bug this suite shipped before catching:

            Isha  "i need aloo" / "2kg" / "what is the price?"
            shop  "500 for 1kg"
            Isha  "i need 2 kilos"

        "500 for 1kg" states a PER-KILO rate, so two kilos is Rs 1000. An
        earlier rule asked only whether the quote mentioned a measure at all,
        saw the "1kg", called it a bundle total and booked Rs 500 -- half the
        real bill. The size of the quantity is the signal, not its presence.
        """
        order = self._order([
            ("hello sir", False), ("i need aloo", False), ("2kg", False),
            ("what is the price?", False),
            ("500 for 1kg", True),
            ("i need 2 kilos", False),
        ])
        self.assertEqual(order.items[0].quantity, 2)
        self.assertEqual(order.items[0].unit, "kg")
        self.assertEqual(order.items[0].unit_price, 500.0)
        self.assertEqual(order.amount, 1000.0)

    def test_ordering_off_a_bundle_quote_is_flagged_not_divided(self):
        """A bundle priced for 2kg but 5kg ordered must not be scaled by guess."""
        order = self._order([
            ("tamatar chaiye", False),
            ("2kg for", True), ("500 only", True),
            ("5 kilo chahiye", False),
        ])
        self.assertIn("bundle-quantity-mismatch", order.signals)

    def test_a_bare_price_is_a_per_unit_rate(self):
        order = self._order([
            ("is there an availability of blue kurtis", False),
            ("yes, 500 ka", True),
            ("mujhe 3 kharidne hai", False),
        ], "apparel")
        self.assertEqual(order.items[0].unit_price, 500.0)
        self.assertEqual(order.amount, 1500.0)

    def test_an_explicit_each_still_multiplies(self):
        order = self._order([
            ("red saree hai kya", False),
            ("haan, 1200 each", True),
            ("i want 2 pieces", False),
        ], "apparel")
        self.assertEqual(order.amount, 2400.0)

    def test_quantity_falls_back_to_the_topic(self):
        """"haan bhej do" carries no number; the earlier 2kg is the answer."""
        order = self._order([
            ("2kg tamatar chaiye", False),
            ("organic grown tamatar", True),
            ("haan bhej do", False),
        ])
        self.assertEqual(order.items[0].quantity, 2)
        self.assertEqual(order.items[0].unit, "kg")

    def test_a_later_bare_mention_does_not_erase_an_earlier_one(self):
        order = self._order([
            ("2kg tamatar chaiye", False),
            ("tamatar", True),
            ("bhej do", False),
        ])
        self.assertEqual(order.items[0].quantity, 2)


class TestSides(unittest.TestCase):
    def test_you_is_always_the_shop(self):
        self.assertTrue(is_shopkeeper("You", "Rahul Verma"))

    def test_anyone_who_is_not_the_customer_is_the_shop(self):
        self.assertTrue(is_shopkeeper("Sharma Kirana", "Rahul Verma"))
        self.assertFalse(is_shopkeeper("Rahul Verma", "Rahul Verma"))

    def test_matching_ignores_case_and_padding(self):
        self.assertFalse(is_shopkeeper("  rahul verma ", "Rahul Verma"))


EXPORT = """26/08/26, 9:41 am - Rahul Verma: is there an availability of blue kurtis
26/08/26, 9:43 am - Sharma Kirana: haan hai, 500 ka
26/08/26, 9:44 am - Rahul Verma: mujhe 3 kharidne hai, udhaar likh lena
28/08/26, 4:00 pm - Rahul Verma: kaise ho
28/08/26, 4:01 pm - Sharma Kirana: badhiya
"""


class TestExportIdempotency(unittest.TestCase):
    """Re-importing the same export must never book the same order twice."""

    def test_first_import_finds_the_order(self):
        result = ingest_conversation(
            EXPORT, ChatCursor(chat_id="c1"), customer="Rahul Verma", category="apparel"
        )
        self.assertEqual(len(result.orders), 1)
        self.assertEqual(result.orders[0].amount, 1500.0)
        self.assertEqual(result.orders[0].mode, "udhaar")

    def test_second_import_finds_nothing(self):
        cursor = ChatCursor(chat_id="c1")
        ingest_conversation(EXPORT, cursor, customer="Rahul Verma", category="apparel")
        cursor.advance(parse_export(EXPORT))

        again = ingest_conversation(
            EXPORT, cursor, customer="Rahul Verma", category="apparel"
        )
        self.assertEqual(again.new_messages, 0)
        self.assertEqual(again.orders, [])

    def test_only_the_appended_order_comes_back(self):
        cursor = ChatCursor(chat_id="c1")
        ingest_conversation(EXPORT, cursor, customer="Rahul Verma", category="apparel")
        cursor.advance(parse_export(EXPORT))

        extended = EXPORT + "29/08/26, 10:00 am - Rahul Verma: 2 saree chahiye 800 each, cash\n"
        result = ingest_conversation(
            extended, cursor, customer="Rahul Verma", category="apparel"
        )
        self.assertEqual(result.new_messages, 1)
        self.assertEqual(len(result.orders), 1)
        self.assertEqual(result.orders[0].amount, 1600.0)

    def test_the_cursor_is_not_advanced_by_ingestion(self):
        """The client advances it after approval, so a crash re-offers."""
        cursor = ChatCursor(chat_id="c1")
        ingest_conversation(EXPORT, cursor, customer="Rahul Verma", category="apparel")
        self.assertIsNone(cursor.last_timestamp)
        self.assertEqual(cursor.seen_hashes, set())


if __name__ == "__main__":
    unittest.main()
