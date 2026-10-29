"""A real iPhone export: WhatsApp's own notes must never reach an order."""

import unittest
from pathlib import Path

from app.extraction.whatsapp import ChatCursor, ingest_conversation, parse_export

CHAT = (Path(__file__).parent / "fixtures" / "isha_clothing_ios.txt").read_text()


class IosExportTest(unittest.TestCase):
    def test_notes_media_and_edits_are_not_messages(self):
        bodies = [m.body for m in parse_export(CHAT)]
        for junk in ("omitted", "changed the group name", "created this group", "added you", "<This message was edited>"):
            self.assertFalse(any(junk in b for b in bodies), junk)
        self.assertIn("Okay and How will you pay?", bodies)
        self.assertTrue(all(b.strip() for b in bodies))

    def test_no_amount_comes_from_a_date_or_a_reminder(self):
        orders = ingest_conversation(CHAT, ChatCursor("isha"), customer="Isha Aggarwal NMIMS",
                                     category="apparel", shop_aliases={"Aadit"}).orders
        self.assertEqual(len(orders), 3)
        # The 30 Sep order, stated by the shop: exactly right.
        self.assertEqual(orders[1].amount, 5000.0)
        self.assertEqual(sorted((i.modifier, i.quantity) for i in orders[1].items), [("blue", 2.0), ("yellow", 8.0)])
        # Never a figure from "19/09/26" or from the Rs 1,000 reminder.
        for o in orders:
            self.assertNotIn(o.amount, (19.0, 26.0, 47.0, 1000.0))


if __name__ == "__main__":
    unittest.main()
