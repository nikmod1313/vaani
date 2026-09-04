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
